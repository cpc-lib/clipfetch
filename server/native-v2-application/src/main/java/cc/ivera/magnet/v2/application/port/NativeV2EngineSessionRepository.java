package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.task.MagnetV2EngineSession;

import java.util.Optional;

/** 每个资源恰好一个 Worker 会话；active_task_id 由条件更新占用。 */
public interface NativeV2EngineSessionRepository {

    void ensureExists(long resourceId, String engineSessionId);

    Optional<MagnetV2EngineSession> find(long resourceId);

    int claimActiveTask(long resourceId, long taskId, long userId);

    int clearActiveTask(long resourceId, long taskId);

    void updateState(long resourceId, String state);

    void updateResume(long resourceId, long version);
}
