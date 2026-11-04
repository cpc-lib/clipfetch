package cc.ivera.magnet.v2.engine.spi;

import java.util.List;

/** 全量下发文件优先级：与 torrent 文件下标一一对应，0 表示不下载。 */
public record UpdatePrioritiesCommand(EngineEnvelope envelope,
                                      List<Integer> filePriorities) {
}
