package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2TaskRepository;
import cc.ivera.magnet.v2.domain.MagnetV2DownloadTaskMapper;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.TaskStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MagnetV2TaskRepositoryAdapter implements NativeV2TaskRepository {

    private final MagnetV2DownloadTaskMapper mapper;

    public MagnetV2TaskRepositoryAdapter(MagnetV2DownloadTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MagnetV2DownloadTask insert(MagnetV2DownloadTask task) {
        mapper.insert(task);
        return task;
    }

    @Override
    public Optional<MagnetV2DownloadTask> findOwned(String taskUuid, long userId) {
        return mapper.findOwned(taskUuid, userId);
    }

    @Override
    public Optional<MagnetV2DownloadTask> findById(long id) {
        return mapper.findById(id);
    }

    @Override
    public List<MagnetV2DownloadTask> listOwned(long userId, int offset, int limit) {
        return mapper.listOwned(userId, offset, limit);
    }

    @Override
    public List<MagnetV2DownloadTask> findDue(Collection<TaskStatus> statuses,
                                              LocalDateTime now, int limit) {
        return mapper.findDue(statuses.stream().map(TaskStatus::name).toList(), now, limit);
    }

    @Override
    public int updateCas(long id, long userId, long expectedRevision, TaskStatus status,
                         long verifiedBytes, int retryCount, LocalDateTime nextRetryAt,
                         String errorCode, String errorMessage) {
        return mapper.updateCas(id, userId, expectedRevision, status.name(), verifiedBytes,
                retryCount, nextRetryAt, errorCode, errorMessage);
    }
}
