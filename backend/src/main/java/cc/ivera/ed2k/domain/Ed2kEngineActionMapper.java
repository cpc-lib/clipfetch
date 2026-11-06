package cc.ivera.ed2k.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** All SQL lives in mapper/Ed2kEngineActionMapper.xml. */
public interface Ed2kEngineActionMapper extends BaseMapper<Ed2kEngineAction> {

    Ed2kEngineAction findByUuid(@Param("actionUuid") String actionUuid);

    Ed2kEngineAction findByDedup(@Param("resourceId") Long resourceId,
                                 @Param("targetRevision") long targetRevision,
                                 @Param("action") String action);

    List<Ed2kEngineAction> listRunnableActions(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

    int countPendingByResource(@Param("resourceId") Long resourceId);

    int insertAction(@Param("action") Ed2kEngineAction action);

    int supersedeOlderActions(@Param("resourceId") Long resourceId,
                              @Param("targetRevision") long targetRevision);

    int claimApplyingCas(@Param("id") Long id,
                         @Param("expectedAttemptCount") int expectedAttemptCount);

    int markApplied(@Param("id") Long id);

    int markRetryWait(@Param("id") Long id,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("lastErrorCode") String lastErrorCode,
                      @Param("lastError") String lastError);

    int markFailed(@Param("id") Long id,
                   @Param("lastErrorCode") String lastErrorCode,
                   @Param("lastError") String lastError);

    int resetStuckApplyingOnStartup();
}
