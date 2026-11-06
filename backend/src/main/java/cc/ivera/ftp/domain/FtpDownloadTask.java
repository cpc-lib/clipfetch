package cc.ivera.ftp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ftp_download_task")
public class FtpDownloadTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskUuid;
    private Long userId;
    private String protocol;
    private String host;
    private Integer port;
    @JsonIgnore
    private String remotePath;
    private String displayName;
    private String status;
    private Long totalBytes;
    private Long downloadedBytes;
    private String expectedSha256;
    private Long revision;
    private Long executionEpoch;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
