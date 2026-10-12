package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 磁力解析历史：保存磁力链接解析出的元数据与文件清单。
 * 下载任务状态由 qBittorrent 管理，本表不维护下载进度。
 */
@TableName("magnet_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 磁力 Info Hash（btih 40 位十六进制，小写） */
    private String infoHash;

    /** 磁力链接 */
    private String magnetUri;

    /** 种子名称 */
    private String name;

    /** 总大小（字节） */
    private Long totalSize;

    /** 文件清单 JSON：[{index, path, size}] */
    private String filesJson;

    /** 解析状态：PARSING=解析中 SUCCEEDED=成功 FAILED=失败 */
    private String status;

    /** 解析失败原因 */
    private String error;

    /** 逻辑删除标记：0=正常 非0=已删除（值为记录 id，避免唯一索引冲突） */
    @TableLogic
    private Long deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
