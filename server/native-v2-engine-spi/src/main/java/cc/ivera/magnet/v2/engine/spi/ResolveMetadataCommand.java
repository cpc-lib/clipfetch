package cc.ivera.magnet.v2.engine.spi;

import java.util.List;

/**
 * 仅解析元数据：按 BTIH/BTMH 与受控 Tracker 建立 metadata-only 临时会话；
 * 直接提供 torrentFile 字节时免于网络解析。
 */
public record ResolveMetadataCommand(EngineEnvelope envelope,
                                     byte[] btih,
                                     byte[] btmh,
                                     List<String> trackers,
                                     byte[] torrentFile) {
}
