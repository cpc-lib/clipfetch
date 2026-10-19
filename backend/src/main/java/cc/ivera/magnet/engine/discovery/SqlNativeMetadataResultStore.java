package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.domain.TorrentNativeParseResult;
import cc.ivera.magnet.domain.TorrentNativeParseResultMapper;
import cc.ivera.magnet.domain.TorrentNativeMetadata;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Verified metadata result persistence, scoped to owner and opaque UUID.
 * Shared piece metadata contains only verified public info bytes.
 */
@Component
@RequiredArgsConstructor
public class SqlNativeMetadataResultStore implements NativeMetadataResultStore {
    private static final int MAX_FILES = 20000;
    private static final int MAX_JSON_CHARS = 4 * 1024 * 1024;
    private final TorrentNativeParseResultMapper mapper;
    private final TorrentNativeTaskMapper metadataMapper;
    private final ObjectMapper json;

    @Transactional(rollbackFor=Exception.class)
    @Override public void save(long userId, UUID jobId, TorrentInfo info) {
        saveVerified(userId,jobId,info,null);
    }

    @Transactional(rollbackFor=Exception.class)
    @Override public void saveVerified(long userId,UUID jobId,TorrentInfo info,byte[] bytes) {
        if(info==null)throw new IllegalArgumentException("Missing metadata");
        if(bytes!=null) {
            if(bytes.length==0 || bytes.length>8*1024*1024)
                throw new IllegalArgumentException("Verified metainfo size out of bounds");
            var parsed=new cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser().parse(bytes);
            if(!parsed.infoHash().equalsIgnoreCase(info.infoHash()) ||
                    parsed.totalBytes()!=info.totalBytes() ||
                    parsed.files().size()!=info.files().size())
                throw new SecurityException("Persisted metainfo mismatch");
            cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy
                    .verifyTorrent(bytes,parsed.infoHash()).requirePublicPermit();
        }
        if(userId <= 0 || jobId == null || info == null ||
                !info.infoHash().matches("(?i)[0-9a-f]{40}") ||
                info.files().size() > MAX_FILES)
            throw new IllegalArgumentException("Invalid verified Metadata result");
        List<NativeMetadataJobManager.FileSummary> files=info.files().stream()
                .map(f->new NativeMetadataJobManager.FileSummary(f.index(),f.path(),f.length()))
                .toList();
        String serialized;
        try { serialized=json.writeValueAsString(files); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Metadata file serialization failed",e); }
        if(serialized.length()>MAX_JSON_CHARS)
            throw new IllegalArgumentException("Metadata file listing exceeds persistence limit");
        if(bytes!=null)savePieceMetadata(info,bytes);
        var record=TorrentNativeParseResult.builder()
                .userId(userId).jobUuid(jobId.toString()).infoHash(info.infoHash())
                .displayName(info.name()).totalBytes(info.totalBytes())
                .filesJson(serialized).verifiedTorrentBytes(bytes==null?null:bytes.clone())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).deleted(0L).build();
        if(mapper.insertSuccess(record)!=1)
            throw new IllegalStateException("Metadata persistence failed");
    }

    private void savePieceMetadata(TorrentInfo info,byte[] bytes) {
        byte[] rawInfo=new BencodeDecoder().decodeTorrent(bytes).infoBytes();
        byte[] header="d4:info".getBytes(StandardCharsets.US_ASCII);
        byte[] safe=new byte[header.length+rawInfo.length+1];
        System.arraycopy(header,0,safe,0,header.length);
        System.arraycopy(rawInfo,0,safe,header.length,rawInfo.length);
        safe[safe.length-1]='e';
        String digest;
        try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(safe));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        var metadata=TorrentNativeMetadata.builder().infoHash(info.infoHash())
                .torrentBencode(safe).metainfoSha256(digest).pieceLength(info.pieceLength())
                .pieceCount((long)info.pieceLayout().pieceCount()).totalBytes(info.totalBytes())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        metadataMapper.insertMetadataIfAbsent(metadata);
        var stored=metadataMapper.findMetadataByHash(info.infoHash());
        if(stored==null || !digest.equalsIgnoreCase(stored.getMetainfoSha256()) ||
                !MessageDigest.isEqual(safe,stored.getTorrentBencode()))
            throw new SecurityException("Existing torrent metadata does not match verified info");
    }

    @Override public Optional<byte[]> findVerified(long userId,UUID jobId) {
        if(userId<=0 || jobId==null)return Optional.empty();
        var row=mapper.findOwned(userId,jobId.toString());
        if(row==null || row.getVerifiedTorrentBytes()==null)return Optional.empty();
        byte[] bytes=row.getVerifiedTorrentBytes();
        if(bytes.length==0 || bytes.length>8*1024*1024)
            throw new SecurityException("Invalid stored metainfo size");
        var parsed=new cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser().parse(bytes);
        if(!parsed.infoHash().equalsIgnoreCase(row.getInfoHash()) ||
                parsed.totalBytes()!=row.getTotalBytes())
            throw new SecurityException("Stored metainfo integrity mismatch");
        cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy
                .verifyTorrent(bytes,parsed.infoHash()).requirePublicPermit();
        return Optional.of(bytes.clone());
    }

    @Override public Optional<byte[]> findVerifiedByInfoHash(String infoHash) {
        if(infoHash==null || !infoHash.matches("(?i)[0-9a-f]{40}"))return Optional.empty();
        var stored=metadataMapper.findMetadataByHash(infoHash.toLowerCase(java.util.Locale.ROOT));
        if(stored==null || stored.getTorrentBencode()==null ||
                stored.getTorrentBencode().length==0 ||
                stored.getTorrentBencode().length>8*1024*1024)return Optional.empty();
        try {
            byte[] bytes=stored.getTorrentBencode();
            String digest;
            try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
            catch(NoSuchAlgorithmException e) {return Optional.empty();}
            if(stored.getMetainfoSha256()==null || !digest.equalsIgnoreCase(stored.getMetainfoSha256()))
                return Optional.empty();
            var parsed=new cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser().parse(bytes);
            if(!parsed.infoHash().equalsIgnoreCase(infoHash))return Optional.empty();
            cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy
                    .verifyTorrent(bytes,parsed.infoHash()).requirePublicPermit();
            return Optional.of(bytes.clone());
        }catch(RuntimeException corrupt) {
            return Optional.empty();
        }
    }

    @Override public Optional<Saved> find(long userId, UUID jobId) {
        if(userId<=0 || jobId==null)return Optional.empty();
        var record=mapper.findOwned(userId,jobId.toString());
        if(record==null)return Optional.empty();
        try {
            var files=json.readValue(record.getFilesJson(),
                    new TypeReference<List<NativeMetadataJobManager.FileSummary>>(){});
            return Optional.of(new Saved(jobId,record.getInfoHash(),record.getDisplayName(),
                    record.getTotalBytes(),files,
                    record.getCreatedAt().toInstant(ZoneOffset.UTC)));
        } catch(JsonProcessingException e) {
            throw new IllegalStateException("Persisted Metadata file listing is corrupt",e);
        }
    }
}
