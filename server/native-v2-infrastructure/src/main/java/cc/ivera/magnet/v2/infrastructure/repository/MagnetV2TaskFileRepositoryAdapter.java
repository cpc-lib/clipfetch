package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2TaskFileRepository;
import cc.ivera.magnet.v2.domain.MagnetV2DownloadTaskFileMapper;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MagnetV2TaskFileRepositoryAdapter implements NativeV2TaskFileRepository {

    private final MagnetV2DownloadTaskFileMapper mapper;

    public MagnetV2TaskFileRepositoryAdapter(MagnetV2DownloadTaskFileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertAll(List<MagnetV2DownloadTaskFile> files) {
        if (!files.isEmpty()) {
            mapper.insertBatch(files);
        }
    }

    @Override
    public List<MagnetV2DownloadTaskFile> findByTask(long taskId) {
        return mapper.findByTask(taskId);
    }

    @Override
    public void updateVerified(long taskId, int fileIndex, long verifiedBytes, String state) {
        mapper.updateVerified(taskId, fileIndex, verifiedBytes, state);
    }

    @Override
    public int deleteByTaskAndUser(long taskId, long userId) {
        return mapper.logicalDeleteByTask(taskId, userId);
    }
}
