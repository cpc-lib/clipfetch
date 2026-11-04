package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface MagnetV2DownloadTaskFileMapper {

    int insertBatch(@Param("files") List<MagnetV2DownloadTaskFile> files);

    List<MagnetV2DownloadTaskFile> findByTask(@Param("taskId") long taskId);

    int updateVerified(@Param("taskId") long taskId,
                       @Param("fileIndex") int fileIndex,
                       @Param("verifiedBytes") long verifiedBytes,
                       @Param("state") String state);

    Optional<MagnetV2DownloadTaskFile> findOwned(@Param("id") long id,
                                                 @Param("userId") long userId);

    int logicalDelete(@Param("id") long id, @Param("userId") long userId);
}
