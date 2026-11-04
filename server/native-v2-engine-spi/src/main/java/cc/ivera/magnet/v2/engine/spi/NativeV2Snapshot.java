package cc.ivera.magnet.v2.engine.spi;

import java.util.List;

/**
 * 低开销快照：观测事实而非授权真相。
 * state 为 DISCOVERING / METADATA_READY / ACTIVE / IDLE / RECOVERING / STOPPED / FAILED；
 * fileVerifiedBytes 与 torrent 文件下标一一对应，是逐文件已通过 piece 校验的字节数。
 */
public record NativeV2Snapshot(String state,
                               boolean metadataReady,
                               long verifiedBytes,
                               long downloadRateBytesPerSec,
                               long uploadRateBytesPerSec,
                               int peerCount,
                               List<Long> fileVerifiedBytes) {
}
