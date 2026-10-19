package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Each row owns exactly one native task; infoHash is intentionally NOT unique.
 * Mutations must include both user_id and task_uuid, never info_hash alone.
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("torrent_native_task")
public class TorrentNativeTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskUuid;
    private Long userId;
    private Long parseId;
    private String infoHash;
    private Long metadataId;
    /** Per-task public tracker URLs, never included in shared metadata or task API. */
    private String trackerUrlsJson;
    private String status;
    private String selectedFilesJson;
    private Long selectedTotalBytes;
    private Long verifiedSelectedBytes;
    private Long downloadedWireBytes;
    private Long revision;
    private Long executionEpoch;
    private String executionInstanceId;
    private String statePath;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    @TableLogic
    private Long deleted;
}
