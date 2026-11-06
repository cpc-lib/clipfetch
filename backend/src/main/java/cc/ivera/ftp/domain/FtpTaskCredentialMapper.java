package cc.ivera.ftp.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface FtpTaskCredentialMapper extends BaseMapper<FtpTaskCredential> {
    int insertCredential(@Param("credential") FtpTaskCredential credential);

    FtpTaskCredential findOwnedByTaskId(@Param("taskId") Long taskId,
                                        @Param("userId") Long userId);

    int logicalDeleteOwned(@Param("taskId") Long taskId,
                           @Param("userId") Long userId);
}
