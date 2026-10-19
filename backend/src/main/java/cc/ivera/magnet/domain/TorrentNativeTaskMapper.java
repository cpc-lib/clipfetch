package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** All SQL lives in mapper/TorrentNativeTaskMapper.xml. */
public interface TorrentNativeTaskMapper extends BaseMapper<TorrentNativeTask> {
    TorrentNativeTask findOwned(@Param("taskUuid") String taskUuid, @Param("userId") Long userId);
    TorrentNativeMetadata findMetadataByHash(@Param("infoHash") String infoHash);

    int insertMetadata(@Param("metadata") TorrentNativeMetadata metadata);
    int insertMetadataIfAbsent(@Param("metadata") TorrentNativeMetadata metadata);

    int insertCreatedTask(@Param("task") TorrentNativeTask task);

    int insertTaskFile(@Param("taskUuid") String taskUuid,
                       @Param("userId") long userId,
                       @Param("fileIndex") int fileIndex,
                       @Param("relativePath") String relativePath,
                       @Param("totalBytes") long totalBytes,
                       @Param("selected") boolean selected);

    int countOutstandingOwned(@Param("userId") long userId);

    List<TorrentNativeTask> listOwned(@Param("userId") Long userId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /** Read-only bounded orphan audit; not proof of exclusive JVM ownership. */
    List<TorrentNativeTask> listRunningForAudit(@Param("limit") int limit);

    int transitionCas(@Param("taskUuid") String taskUuid,
                      @Param("userId") Long userId,
                      @Param("expectedRevision") long expectedRevision,
                      @Param("expectedStatus") String expectedStatus,
                      @Param("newStatus") String newStatus);

    /** Atomically create the next execution generation for a READY task. */
    int claimExecutionFence(@Param("taskUuid") String taskUuid,
                            @Param("userId") long userId,
                            @Param("expectedRevision") long expectedRevision,
                            @Param("instanceId") String instanceId);

    /** Only the exact generation, instance and revision may publish progress. */
    int updateFencedProgress(@Param("taskUuid") String taskUuid,
                             @Param("userId") long userId,
                             @Param("expectedRevision") long expectedRevision,
                             @Param("epoch") long epoch,
                             @Param("instanceId") String instanceId,
                             @Param("verifiedBytes") long verifiedBytes,
                             @Param("wireBytes") long wireBytes,
                             @Param("completed") boolean completed);

    /** Worker updates may only affect a RUNNING task at its current revision. */
    int updateProgressCas(@Param("taskUuid") String taskUuid,
                          @Param("userId") Long userId,
                          @Param("expectedRevision") long expectedRevision,
                          @Param("verifiedSelectedBytes") long verifiedSelectedBytes,
                          @Param("downloadedWireBytes") long downloadedWireBytes,
                          @Param("completed") boolean completed);

    /** Crash/disk recheck may REDUCE progress; only allowed when no Worker is RUNNING. */
    int reconcileProgressCas(@Param("taskUuid") String taskUuid,
                             @Param("userId") Long userId,
                             @Param("expectedRevision") long expectedRevision,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("verifiedSelectedBytes") long verifiedSelectedBytes,
                             @Param("newStatus") String newStatus);

    /** Logical delete is CAS-fenced and unavailable while RUNNING. */
    int logicalDeleteCas(@Param("taskUuid") String taskUuid,
                         @Param("userId") Long userId,
                         @Param("expectedRevision") long expectedRevision,
                         @Param("expectedStatus") String expectedStatus);
}
