package cc.ivera.preparse.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 预解析库：用户预保存的视频链接，解析成功后标记 parsed=1。
 */
@TableName("pre_parse")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreParse {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 视频链接 */
    private String url;

    /** 文件名称（可空，不填自动生成） */
    private String title;

    /** 解析状态：0=未解析 1=已解析 */
    private Boolean parsed;

    /** 逻辑删除标记：0=正常 非0=已删除（值为记录 id，避免唯一索引冲突） */
    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;
}
