package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 一个实际 qBittorrent 种子只能由一个用户任务持有。 */
@Data
@TableName("magnet_download_task")
public class MagnetDownloadTask {
    @TableId
    private String taskUuid;
    private Long userId;
    private Long parseId;
    private String infoHash;
    private String savePath;
    private String selectedFilesJson;
    private LocalDateTime createdAt;
    /** 下载到 100% 后回写：COMPLETED */
    private String status;
    private LocalDateTime completedAt;
    private Long totalSize;
}
