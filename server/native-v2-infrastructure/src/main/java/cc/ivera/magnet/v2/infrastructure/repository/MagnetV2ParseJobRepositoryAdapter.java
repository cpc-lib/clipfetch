package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.domain.MagnetV2ParseJobMapper;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MagnetV2ParseJobRepositoryAdapter implements NativeV2ParseJobRepository {

    private final MagnetV2ParseJobMapper mapper;

    public MagnetV2ParseJobRepositoryAdapter(MagnetV2ParseJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MagnetV2ParseJob insert(MagnetV2ParseJob job) {
        try {
            mapper.insert(job);
            return job;
        } catch (DuplicateKeyException error) {
            throw new cc.ivera.magnet.v2.application.V2ConflictException("解析任务已存在");
        }
    }

    @Override
    public Optional<MagnetV2ParseJob> findByUuid(String parseUuid) {
        return mapper.findByUuid(parseUuid);
    }

    @Override
    public Optional<MagnetV2ParseJob> findOwned(String parseUuid, long userId) {
        return mapper.findOwned(parseUuid, userId);
    }

    @Override
    public List<MagnetV2ParseJob> listOwned(long userId, int offset, int limit) {
        return mapper.listOwned(userId, offset, limit);
    }

    @Override
    public List<MagnetV2ParseJob> findDue(LocalDateTime now, int limit) {
        return mapper.findDue(now, limit);
    }

    @Override
    public int claimPending(long id, long expectedRevision) {
        return mapper.claimPending(id, expectedRevision);
    }

    @Override
    public int markReady(long id, long userId, long expectedRevision, long resourceId) {
        return mapper.markReady(id, userId, expectedRevision, resourceId);
    }

    @Override
    public int markFailed(long id, long userId, long expectedRevision,
                          String errorCode, String errorMessage) {
        return mapper.markFailed(id, userId, expectedRevision, errorCode, errorMessage);
    }
}
