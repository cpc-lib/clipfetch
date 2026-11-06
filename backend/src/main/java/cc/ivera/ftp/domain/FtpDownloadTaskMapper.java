package cc.ivera.ftp.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FtpDownloadTaskMapper extends BaseMapper<FtpDownloadTask> {
    int insertTask(@Param("task") FtpDownloadTask task);

    FtpDownloadTask findOwnedByUuid(@Param("taskUuid") String taskUuid,
                                    @Param("userId") Long userId);

    List<FtpDownloadTask> listOwned(@Param("userId") Long userId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    int countOwned(@Param("userId") Long userId);

    int countActiveOwned(@Param("userId") Long userId);

    List<FtpDownloadTask> listRecoverable(@Param("limit") int limit,
                                          @Param("afterId") long afterId);

    int claimQueued(@Param("id") Long id,
                    @Param("userId") Long userId,
                    @Param("expectedRevision") long expectedRevision);

    int updateStateCas(@Param("taskUuid") String taskUuid,
                       @Param("userId") Long userId,
                       @Param("expectedRevision") long expectedRevision,
                       @Param("status") String status,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage);

    int updateWorkerState(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("executionEpoch") long executionEpoch,
                          @Param("status") String status,
                          @Param("downloadedBytes") long downloadedBytes,
                          @Param("totalBytes") Long totalBytes,
                          @Param("retryCount") int retryCount,
                          @Param("nextRetryAt") LocalDateTime nextRetryAt,
                          @Param("errorCode") String errorCode,
                          @Param("errorMessage") String errorMessage,
                          @Param("completedAt") LocalDateTime completedAt);

    int updateWorkerProgress(@Param("id") Long id,
                             @Param("userId") Long userId,
                             @Param("executionEpoch") long executionEpoch,
                             @Param("downloadedBytes") long downloadedBytes,
                             @Param("totalBytes") Long totalBytes);

    int resetForRestart(@Param("taskUuid") String taskUuid,
                        @Param("userId") Long userId,
                        @Param("expectedRevision") long expectedRevision);

    int logicalDeleteOwned(@Param("id") Long id,
                           @Param("taskUuid") String taskUuid,
                           @Param("userId") Long userId,
                           @Param("expectedRevision") long expectedRevision);
}
