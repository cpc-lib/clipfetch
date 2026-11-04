package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MagnetV2DownloadTaskMapper {

    int insert(MagnetV2DownloadTask task);

    Optional<MagnetV2DownloadTask> findById(@Param("id") long id);

    Optional<MagnetV2DownloadTask> findOwned(@Param("taskUuid") String taskUuid,
                                             @Param("userId") long userId);

    List<MagnetV2DownloadTask> listOwned(@Param("userId") long userId,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    int updateCas(@Param("id") long id,
                  @Param("userId") long userId,
                  @Param("expectedRevision") long expectedRevision,
                  @Param("status") String status,
                  @Param("verifiedBytes") long verifiedBytes,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage);

    int updateStatusCas(@Param("id") long id,
                        @Param("userId") long userId,
                        @Param("expectedRevision") long expectedRevision,
                        @Param("status") String status,
                        @Param("newRevision") long newRevision,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage);

    int logicalDelete(@Param("id") long id, @Param("userId") long userId);

    List<MagnetV2DownloadTask> findDue(@Param("statuses") List<String> statuses,
                                       @Param("now") LocalDateTime now,
                                       @Param("limit") int limit);
}
