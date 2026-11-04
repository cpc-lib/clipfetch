package cc.ivera.magnet.v2.engine.spi;

import java.util.List;

/**
 * 挂载资源会话：携带 .torrent 元数据（可为空字节，表示按哈希走 DHT 抓元数据）、
 * 可选 resume data、Worker 下载根下的存储路径与受控 Tracker 列表。
 */
public record AddTorrentCommand(EngineEnvelope envelope,
                                byte[] metadata,
                                byte[] resumeData,
                                String storagePath,
                                boolean metadataOnly,
                                byte[] btih,
                                byte[] btmh,
                                List<String> trackers) {
}
