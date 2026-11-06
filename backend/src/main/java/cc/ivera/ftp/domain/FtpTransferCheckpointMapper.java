package cc.ivera.ftp.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface FtpTransferCheckpointMapper extends BaseMapper<FtpTransferCheckpoint> {
    int insertCheckpoint(@Param("checkpoint") FtpTransferCheckpoint checkpoint);

    FtpTransferCheckpoint findOwnedByTaskId(@Param("taskId") Long taskId,
                                            @Param("userId") Long userId);

    int updateCas(@Param("taskId") Long taskId,
                  @Param("userId") Long userId,
                  @Param("expectedRevision") long expectedRevision,
                  @Param("committedBytes") long committedBytes,
                  @Param("remoteSize") Long remoteSize,
                  @Param("remoteMtime") String remoteMtime,
                  @Param("remoteUniqueId") String remoteUniqueId,
                  @Param("localSha256") String localSha256,
                  @Param("lastVerifiedAt") LocalDateTime lastVerifiedAt);

    int logicalDeleteOwned(@Param("taskId") Long taskId,
                           @Param("userId") Long userId);
}
