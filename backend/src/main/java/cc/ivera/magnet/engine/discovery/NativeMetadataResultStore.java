package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable successful metadata summaries, never downloaded file progress. */
public interface NativeMetadataResultStore {
    record Saved(UUID jobId, String infoHash, String name, long totalBytes,
                 List<NativeMetadataJobManager.FileSummary> files, Instant createdAt) {
        public Saved { files = List.copyOf(files); }
    }
    void save(long userId, UUID jobId, TorrentInfo verified);
    default void saveVerified(long userId,UUID jobId,TorrentInfo verified,byte[] bytes) {
        save(userId,jobId,verified);
    }
    default Optional<byte[]> findVerified(long userId,UUID jobId) {return Optional.empty();}
    /**
     * Verified public metainfo bytes ("d4:info"+info+"e") previously persisted for
     * this exact InfoHash, from any owner; re-parsing the same public torrent must
     * not depend on live Peer availability. Returns empty when absent or invalid.
     */
    default Optional<byte[]> findVerifiedByInfoHash(String infoHash) {return Optional.empty();}
    Optional<Saved> find(long userId, UUID jobId);
    static NativeMetadataResultStore disabled() {
        return new NativeMetadataResultStore() {
            @Override public void save(long userId, UUID jobId, TorrentInfo info) {}
            @Override public Optional<Saved> find(long userId, UUID jobId) { return Optional.empty(); }
        };
    }
}
