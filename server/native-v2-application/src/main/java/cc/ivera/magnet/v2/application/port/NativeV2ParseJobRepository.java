package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 解析任务仓储；状态变更全部使用 revision CAS。 */
public interface NativeV2ParseJobRepository {

    MagnetV2ParseJob insert(MagnetV2ParseJob job);

    Optional<MagnetV2ParseJob> findByUuid(String parseUuid);

    Optional<MagnetV2ParseJob> findOwned(String parseUuid, long userId);

    List<MagnetV2ParseJob> listOwned(long userId, int offset, int limit);

    /** 到期的 PENDING 任务；调度器据此领取。 */
    List<MagnetV2ParseJob> findDue(LocalDateTime now, int limit);

    /** CAS PENDING → RESOLVING，revision + 1 */
    int claimPending(long id, long expectedRevision);

    int markReady(long id, long userId, long expectedRevision, long resourceId);

    int markFailed(long id, long userId, long expectedRevision, String errorCode, String errorMessage);
}
