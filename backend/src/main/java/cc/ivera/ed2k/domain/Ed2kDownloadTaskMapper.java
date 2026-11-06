package cc.ivera.ed2k.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** All SQL lives in mapper/Ed2kDownloadTaskMapper.xml. */
public interface Ed2kDownloadTaskMapper extends BaseMapper<Ed2kDownloadTask> {

    Ed2kDownloadTask findOwnedByUuid(@Param("taskUuid") String taskUuid,
                                     @Param("userId") Long userId);

    Ed2kDownloadTask findActiveByUserAndResource(@Param("userId") Long userId,
                                                 @Param("resourceId") Long resourceId);

    List<Ed2kDownloadTask> listOwned(@Param("userId") Long userId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    int countOwned(@Param("userId") Long userId);

    int countActiveOwned(@Param("userId") Long userId);

    int countActiveDemandByResource(@Param("resourceId") Long resourceId);

    List<Ed2kDownloadTask> listNonDeletedByResource(@Param("resourceId") Long resourceId);

    int insertTask(@Param("task") Ed2kDownloadTask task);

    int updateDesiredStateCas(@Param("taskUuid") String taskUuid,
                              @Param("userId") Long userId,
                              @Param("expectedRevision") long expectedRevision,
                              @Param("desiredState") String desiredState,
                              @Param("taskState") String taskState,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    int updateProjectionByResource(@Param("taskId") Long taskId,
                                   @Param("userId") Long userId,
                                   @Param("expectedRevision") long expectedRevision,
                                   @Param("taskState") String taskState,
                                   @Param("progressBytes") long progressBytes,
                                   @Param("errorCode") String errorCode,
                                   @Param("errorMessage") String errorMessage,
                                   @Param("completedAt") LocalDateTime completedAt);

    int logicalDeleteOwned(@Param("id") Long id,
                           @Param("taskUuid") String taskUuid,
                           @Param("userId") Long userId);
}
