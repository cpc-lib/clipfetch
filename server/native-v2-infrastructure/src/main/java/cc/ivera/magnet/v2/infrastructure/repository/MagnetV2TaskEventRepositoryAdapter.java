package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2TaskEventRepository;
import cc.ivera.magnet.v2.domain.MagnetV2TaskEventMapper;
import cc.ivera.magnet.v2.domain.task.MagnetV2TaskEvent;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MagnetV2TaskEventRepositoryAdapter implements NativeV2TaskEventRepository {

    private final MagnetV2TaskEventMapper mapper;

    public MagnetV2TaskEventRepositoryAdapter(MagnetV2TaskEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(long userId, long taskId, String eventType, String payloadJson) {
        mapper.insert(userId, taskId, eventType, payloadJson);
    }

    @Override
    public List<MagnetV2TaskEvent> listRecent(long taskId, int limit) {
        return mapper.listRecent(taskId, limit);
    }
}
