package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * One selected file of a native task. Reads are always scoped by both
 * user_id and task_uuid; verified_bytes is never maintained after insert and
 * must not be trusted as progress.
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("torrent_native_file")
public class TorrentNativeFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskUuid;
    private Long userId;
    private Integer fileIndex;
    private String relativePath;
    private Long totalBytes;
    private Long verifiedBytes;
    private Boolean isSelected;
}
