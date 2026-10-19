package cc.ivera.magnet.domain;

import org.apache.ibatis.annotations.Param;

public interface TorrentNativeRetryPlanMapper {
    int insertPending(@Param("plan") TorrentNativeRetryPlan plan);
    TorrentNativeRetryPlan findOwned(@Param("owner") long owner,@Param("taskUuid") String taskUuid);
    java.util.List<TorrentNativeRetryPlan> listDue(@Param("now") java.time.LocalDateTime now,
                                                    @Param("limit") int limit);
    int claimDue(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                 @Param("expectedRevision") long expectedRevision,
                 @Param("token") String token,@Param("now") java.time.LocalDateTime now,
                 @Param("until") java.time.LocalDateTime until);
    int bindWorker(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                   @Param("revision") long revision,@Param("token") String token,
                   @Param("jobUuid") String jobUuid,@Param("now") java.time.LocalDateTime now);
    int releaseClaim(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                     @Param("token") String token,@Param("revision") long revision,
                     @Param("next") java.time.LocalDateTime next,
                     @Param("now") java.time.LocalDateTime now);
    int finishWorker(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                     @Param("revision") long revision,@Param("token") String token,
                     @Param("jobUuid") String jobUuid,@Param("outcome") String outcome,
                     @Param("now") java.time.LocalDateTime now);
    int finishClaim(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                    @Param("token") String token,@Param("revision") long revision);
    java.util.List<TorrentNativeRetryPlan> listExpiredClaims(@Param("now") java.time.LocalDateTime now,
                                                              @Param("limit") int limit);
    int expireClaim(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                    @Param("revision") long revision,@Param("now") java.time.LocalDateTime now);
    int stopOwned(@Param("owner") long owner,@Param("taskUuid") String taskUuid,
                  @Param("expectedRevision") long expectedRevision);
}
