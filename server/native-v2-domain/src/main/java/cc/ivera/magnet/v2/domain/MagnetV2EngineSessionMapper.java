package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.task.MagnetV2EngineSession;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

public interface MagnetV2EngineSessionMapper {

    int insertIfAbsent(@Param("resourceId") long resourceId,
                       @Param("engineSessionId") String engineSessionId);

    Optional<MagnetV2EngineSession> findByResource(@Param("resourceId") long resourceId);

    int updateState(@Param("resourceId") long resourceId, @Param("state") String state);

    int updateResume(@Param("resourceId") long resourceId, @Param("version") long version);

    int claimActiveTask(@Param("resourceId") long resourceId,
                        @Param("taskId") long taskId,
                        @Param("userId") long userId);

    int clearActiveTask(@Param("resourceId") long resourceId,
                        @Param("taskId") long taskId);
}
