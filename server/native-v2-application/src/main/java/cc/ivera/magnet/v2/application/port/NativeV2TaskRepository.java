package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.TaskStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 用户任务仓储；所有状态写操作都带 revision CAS。 */
public interface NativeV2TaskRepository {

    MagnetV2DownloadTask insert(MagnetV2DownloadTask task);

    Optional<MagnetV2DownloadTask> findOwned(String taskUuid, long userId);

    Optional<MagnetV2DownloadTask> findById(long id);

    List<MagnetV2DownloadTask> listOwned(long userId, int offset, int limit);

    List<MagnetV2DownloadTask> findDue(Collection<TaskStatus> statuses, LocalDateTime now, int limit);

    int updateCas(long id, long userId, long expectedRevision, TaskStatus status,
                  long verifiedBytes, int retryCount, LocalDateTime nextRetryAt,
                  String errorCode, String errorMessage);

    int deleteByIdAndUser(long id, long userId);
}
