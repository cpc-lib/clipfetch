package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2EngineSessionRepository;
import cc.ivera.magnet.v2.domain.MagnetV2EngineSessionMapper;
import cc.ivera.magnet.v2.domain.task.MagnetV2EngineSession;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MagnetV2EngineSessionRepositoryAdapter implements NativeV2EngineSessionRepository {

    private final MagnetV2EngineSessionMapper mapper;

    public MagnetV2EngineSessionRepositoryAdapter(MagnetV2EngineSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void ensureExists(long resourceId, String engineSessionId) {
        mapper.insertIfAbsent(resourceId, engineSessionId);
    }

    @Override
    public Optional<MagnetV2EngineSession> find(long resourceId) {
        return mapper.findByResource(resourceId);
    }

    @Override
    public int claimActiveTask(long resourceId, long taskId, long userId) {
        return mapper.claimActiveTask(resourceId, taskId, userId);
    }

    @Override
    public int clearActiveTask(long resourceId, long taskId) {
        return mapper.clearActiveTask(resourceId, taskId);
    }

    @Override
    public void updateState(long resourceId, String state) {
        mapper.updateState(resourceId, state);
    }

    @Override
    public void updateResume(long resourceId, long version) {
        mapper.updateResume(resourceId, version);
    }
}
