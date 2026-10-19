package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One active native download root per user. The path is always a server-approved
 * directory picked from the browse API, never a raw client supplied absolute path.
 * Deletion is logical and writes deleted = id (biz_key is fixed to "default").
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("native_download_root")
public class NativeDownloadRoot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String rootPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
