package cc.ivera.ed2k.domain;

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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ed2k_download_task")
public class Ed2kDownloadTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskUuid;
    private Long userId;
    private Long resourceId;
    private String requestedName;
    private String desiredState;
    private String taskState;
    private Long progressBytes;
    private Long revision;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
