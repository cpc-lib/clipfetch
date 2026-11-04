package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;

import java.util.List;

public interface NativeV2TaskFileRepository {

    void insertAll(List<MagnetV2DownloadTaskFile> files);

    List<MagnetV2DownloadTaskFile> findByTask(long taskId);

    void updateVerified(long taskId, int fileIndex, long verifiedBytes, String state);

    int deleteByTaskAndUser(long taskId, long userId);
}
