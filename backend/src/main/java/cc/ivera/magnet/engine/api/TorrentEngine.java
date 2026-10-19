package cc.ivera.magnet.engine.api;

import java.util.Set;
import java.util.UUID;

/**
 * Long-term contract for a genuine, persistent native BitTorrent engine.
 * No fake implementation is registered until the peer/download loop exists.
 * userId is assigned by the authenticated application service.
 */
public interface TorrentEngine {
    EngineCapabilities capabilities();
    UUID create(long userId, long parseId, Set<Integer> selectedFileIndexes);
    TaskSnapshot snapshot(long userId, UUID taskUuid);
    void start(long userId, UUID taskUuid);
    void pause(long userId, UUID taskUuid);
    void delete(long userId, UUID taskUuid, boolean deleteFiles);

    record EngineCapabilities(boolean magnetMetadata, boolean tcpPeerDownload,
                              boolean dht, boolean durableResume) {}

    record TaskSnapshot(UUID taskUuid, long userId, String infoHash, String status,
                        long selectedTotalBytes, long verifiedSelectedBytes,
                        long downloadedWireBytes, long downloadSpeedBytesPerSecond,
                        long revision) {}
}
