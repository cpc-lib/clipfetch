package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTask;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Opens an owner-scoped durable checkpoint using ONLY verified database metadata.
 * The browser never supplies infohash, piece hashes, statePath or selected total.
 * Does not claim RUNNING and never starts a network downloader.
 */
@Service
@RequiredArgsConstructor
public class NativeTaskCheckpointFactory {
    /** A single trust decision binds this already-validated public metainfo to its checkpoint. */
    public record OpenedPublicTask(VerifiedPieceCheckpoint checkpoint,
                                  VerifiedSwarmPrivacy.PublicPermit permit) {}

    private final TorrentNativeTaskMapper mapper;
    private final NativeTorrentProperties settings;
    private final ObjectMapper json;

    public VerifiedPieceCheckpoint openOwned(long owner,UUID taskId) throws IOException {
        return openPublicOwned(owner,taskId).checkpoint();
    }

    public OpenedPublicTask openPublicOwned(long owner,UUID taskId) throws IOException {
        if(owner<=0 || taskId==null)throw new NoSuchElementException("Native task unavailable");
        TorrentNativeTask task=mapper.findOwned(taskId.toString(),owner);
        if(task==null)throw new NoSuchElementException("Native task unavailable");
        if("RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Cannot independently reopen while Worker is active");

        // The persisted state path must still live in an approved base and end with
        // this exact owner/task pair; a user-selected root is never re-derived here.
        Path expected=NativeTaskStoragePaths.assertPersisted(settings,owner,taskId,
                task.getStatePath());

        var meta=mapper.findMetadataByHash(task.getInfoHash());
        if(meta==null || task.getMetadataId()==null ||
                !task.getMetadataId().equals(meta.getId()) ||
                meta.getTorrentBencode()==null ||
                meta.getTorrentBencode().length==0 ||
                meta.getTorrentBencode().length>Math.min(settings.getMaxMetadataBytes(),8*1024*1024))
            throw new SecurityException("Task is missing its verified full Piece metadata");
        if(!sha256(meta.getTorrentBencode()).equalsIgnoreCase(meta.getMetainfoSha256()))
            throw new SecurityException("Native metadata SHA-256 mismatch");
        var info=new TorrentV1MetainfoParser().parse(meta.getTorrentBencode());
        if(!info.infoHash().equalsIgnoreCase(task.getInfoHash()) ||
                info.pieceLength()!=meta.getPieceLength() ||
                info.pieceLayout().pieceCount()!=meta.getPieceCount() ||
                info.totalBytes()!=meta.getTotalBytes())
            throw new SecurityException("Native metadata and task database disagree");
        var privacy=VerifiedSwarmPrivacy.verifyTorrent(meta.getTorrentBencode(),info.infoHash());
        var publicPermit=privacy.requirePublicPermit(taskTrackerUrls(task.getTrackerUrlsJson()));

        Set<Integer> selected=selectedFromJson(task.getSelectedFilesJson(),info.files().size());
        if(info.pieceLayout().selectedTotalBytes(selected)!=task.getSelectedTotalBytes())
            throw new SecurityException("Selected file totals do not match persisted task");
        return new OpenedPublicTask(
                new VerifiedPieceCheckpoint(info,selected,expected,owner,taskId),publicPermit);
    }

    private List<String> taskTrackerUrls(String raw) {
        if(raw==null || raw.isBlank())return List.of(); // existing pre-V23 tasks
        if(raw.length()>8192)throw new SecurityException("Invalid persisted task trackers");
        try {
            var node=json.readTree(raw);
            if(node==null || !node.isArray() || node.size()>64)
                throw new SecurityException("Invalid persisted task Tracker list");
            var values=new LinkedHashSet<String>();
            for(var url:node) {
                if(!url.isTextual())throw new SecurityException("Invalid Tracker URI");
                values.add(url.textValue());
            }
            values.addAll(cc.ivera.magnet.domain.DefaultTrackers.TRACKERS);
            return values.stream().limit(64).toList();
        }catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SecurityException("Corrupt task Tracker JSON",e);
        }
    }

    private Set<Integer> selectedFromJson(String raw,int limit) {
        if(raw==null || raw.length()>100000)
            throw new SecurityException("Invalid task selection");
        try {
            var tree=json.readTree(raw);
            if(tree==null || !tree.isArray() || tree.isEmpty() || tree.size()>1000)
                throw new SecurityException("Invalid task selection");
            TreeSet<Integer> selected=new TreeSet<>();
            for(var entry:tree) {
                if(!entry.isInt() || entry.intValue()<0 || entry.intValue()>=limit ||
                        !selected.add(entry.intValue()))
                    throw new SecurityException("Corrupt task file selection");
            }
            return selected;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SecurityException("Corrupt task selection JSON",e);
        }
    }
    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
