package cc.ivera.magnet.v2.engine.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * 命令公共信封：幂等键、资源标识、owner epoch 与时间授权。
 * commandId 由业务操作稳定生成（同一 revision 的重试复用同一 id）；
 * deadline 是单次命令执行截止；leaseUntil 是数据库租约授权截止，命令不能延长。
 */
public record EngineEnvelope(UUID resourceId,
                             String commandId,
                             long ownerEpoch,
                             Instant deadline,
                             Instant leaseUntil) {
}
