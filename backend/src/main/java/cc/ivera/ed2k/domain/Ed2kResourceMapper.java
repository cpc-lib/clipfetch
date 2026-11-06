package cc.ivera.ed2k.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** All SQL lives in mapper/Ed2kResourceMapper.xml. */
public interface Ed2kResourceMapper extends BaseMapper<Ed2kResource> {

    Ed2kResource findById(@Param("id") Long id);

    Ed2kResource findByIdentity(@Param("md4Hash") String md4Hash,
                                @Param("fileSize") Long fileSize);

    List<Ed2kResource> findByIds(@Param("ids") List<Long> ids);

    List<Ed2kResource> listActiveOrPendingResources(@Param("limit") int limit);

    int insertResource(@Param("resource") Ed2kResource resource);

    int bumpRevisionCas(@Param("id") Long id,
                        @Param("expectedRevision") long expectedRevision,
                        @Param("engineState") String engineState,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage);

    int updateStateCas(@Param("id") Long id,
                       @Param("expectedRevision") long expectedRevision,
                       @Param("engineState") String engineState,
                       @Param("engineManaged") boolean engineManaged,
                       @Param("engineRef") String engineRef,
                       @Param("completedPath") String completedPath,
                       @Param("completedVerifiedAt") LocalDateTime completedVerifiedAt,
                       @Param("observedBytes") long observedBytes,
                       @Param("speedBps") long speedBps,
                       @Param("sourcesTotal") int sourcesTotal,
                       @Param("sourcesTransferring") int sourcesTransferring,
                       @Param("lastSnapshotAt") LocalDateTime lastSnapshotAt,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage);
}
