package cc.ivera.magnet.v2.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("magnet_v2_download_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2DownloadTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskUuid;
    private Long userId;
    private Long resourceId;
    private TaskStatus status;
    private Long selectedBytes;
    private Long verifiedBytes;
    private Integer priority;
    private Long speedLimit;
    private Long revision;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
