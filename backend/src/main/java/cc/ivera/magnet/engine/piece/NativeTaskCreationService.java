package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeMetadata;
import cc.ivera.magnet.domain.TorrentNativeTask;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Creates CREATED-only native tasks from full, verified v1 .torrent bytes.
 *
 * The existing parse-result summary cannot provide piece hashes and must never
 * be treated as sufficient evidence for a downloadable task.
 *
 * Only PUBLIC torrents are accepted while metadata storage is globally shared:
 * private announce credentials must not leak through torrent_native_metadata.
 */
@Service
@RequiredArgsConstructor
public class NativeTaskCreationService {
    private static final int MAX_FILES = 1000;
    private final TorrentNativeTaskMapper mapper;
    private final NativeTorrentProperties settings;
    private final ObjectMapper json;

    public record Created(UUID taskUuid,String infoHash,String state,long revision,
                          long selectedBytes,int selectedFiles) {}

    @Transactional(rollbackFor=Exception.class)
    public Created createFromTorrent(long owner,byte[] torrentBytes,List<Integer> indexes) {
        return createFromTorrent(owner,torrentBytes,indexes,null);
    }

    /** {@code storageBase == null} keeps the administrator-configured default root. */
    @Transactional(rollbackFor=Exception.class)
    public Created createFromTorrent(long owner,byte[] torrentBytes,List<Integer> indexes,
                                     Path storageBase) {
        if(owner<=0 || torrentBytes==null || torrentBytes.length==0 ||
                torrentBytes.length>Math.min(8*1024*1024,settings.getMaxMetadataBytes()))
            throw new IllegalArgumentException("Invalid torrent upload or metadata size");
        if(indexes==null || indexes.isEmpty() || indexes.size()>MAX_FILES)
            throw new IllegalArgumentException("Choose 1 to 1000 files");

        var info=new TorrentV1MetainfoParser().parse(torrentBytes);
        var classification=VerifiedSwarmPrivacy.verifyTorrent(torrentBytes,info.infoHash());
        if(classification.privateTorrent())
            throw new SecurityException("Private torrents require per-owner protected metadata; not supported yet");
        if(info.files().size()>MAX_FILES)
            throw new IllegalArgumentException("This prototype supports up to 1000 torrent files");

        var selected=new TreeSet<Integer>();
        for(Integer index:indexes) {
            if(index==null || index<0 || index>=info.files().size() || !selected.add(index))
                throw new IllegalArgumentException("Invalid or duplicate selected file index");
        }
        for(var file:info.files()) {
            if(file.path().length()>1024)
                throw new IllegalArgumentException("Torrent file path exceeds database limit");
        }
        long selectedBytes=info.pieceLayout().selectedTotalBytes(selected);
        if(selectedBytes<=0)
            throw new IllegalArgumentException("Selected files contain no bytes");
        int cap=settings.getMaxActiveTasksPerUser();
        if(cap<1 || mapper.countOutstandingOwned(owner)>=cap)
            throw new IllegalStateException("Native task quota reached");

        // Preserve exact info bytes for SHA-1 verification, but strip announce fields,
        // webseeds, URL credentials and other uploader-controlled root metadata.
        byte[] rawInfo=new BencodeDecoder().decodeTorrent(torrentBytes).infoBytes();
        byte[] header="d4:info".getBytes(StandardCharsets.US_ASCII);
        byte[] safe=new byte[header.length+rawInfo.length+1];
        System.arraycopy(header,0,safe,0,header.length);
        System.arraycopy(rawInfo,0,safe,header.length,rawInfo.length);
        safe[safe.length-1]='e';
        if(!new TorrentV1MetainfoParser().parse(safe).infoHash().equals(info.infoHash()))
            throw new SecurityException("Torrent info changed during sanitization");
        String digest=sha256(safe);

        var metadata=mapper.findMetadataByHash(info.infoHash());
        if(metadata==null) {
            metadata=TorrentNativeMetadata.builder().infoHash(info.infoHash())
                    .torrentBencode(safe).metainfoSha256(digest)
                    .pieceLength(info.pieceLength())
                    .pieceCount((long)info.pieceLayout().pieceCount())
                    .totalBytes(info.totalBytes())
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
            if(mapper.insertMetadata(metadata)!=1 || metadata.getId()==null)
                throw new IllegalStateException("Unable to save validated torrent metadata");
        } else if(!digest.equalsIgnoreCase(metadata.getMetainfoSha256()) ||
                !MessageDigest.isEqual(safe,metadata.getTorrentBencode())) {
            throw new SecurityException("Existing torrent metadata does not match verified info");
        }

        UUID taskId=UUID.randomUUID();
        String statePath=NativeTaskStoragePaths.resolve(settings,owner,taskId,storageBase).toString();
        String selection;
        String trackersJson;
        try {
            selection=json.writeValueAsString(selected);
            // Torrent root announce URLs are not authenticated by the info hash.
            // Persist only a bounded, screened subset for THIS task owner.
            var candidates = new java.util.LinkedHashSet<>(classification.publicTrackerUrls().stream()
                    .filter(s->s.length()<=512).toList());
            candidates.addAll(cc.ivera.magnet.domain.DefaultTrackers.TRACKERS);
            var trustedCandidates = candidates.stream().limit(64).toList();
            var vetted=classification.requirePublicPermit(trustedCandidates).trackers();
            trackersJson=json.writeValueAsString(vetted.stream().map(Object::toString).toList());
        } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid file selection",e); }
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        var task=TorrentNativeTask.builder().taskUuid(taskId.toString())
                .userId(owner).parseId(0L) // 0 means direct validated .torrent upload.
                .infoHash(info.infoHash()).metadataId(metadata.getId())
                .trackerUrlsJson(trackersJson)
                .status("CREATED").selectedFilesJson(selection)
                .selectedTotalBytes(selectedBytes).verifiedSelectedBytes(0L)
                .downloadedWireBytes(0L).revision(0L).statePath(statePath)
                .createdAt(now).updatedAt(now).deleted(0L).build();
        if(mapper.insertCreatedTask(task)!=1)
            throw new IllegalStateException("Unable to create native task");

        for(var file:info.files()) {
            if(mapper.insertTaskFile(taskId.toString(),owner,file.index(),file.path(),
                    file.length(),selected.contains(file.index()))!=1)
                throw new IllegalStateException("Unable to save selected torrent files");
        }
        // No filesystem mutation or Peer connection occurs here.
        return new Created(taskId,info.infoHash(),"CREATED",0L,selectedBytes,selected.size());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
