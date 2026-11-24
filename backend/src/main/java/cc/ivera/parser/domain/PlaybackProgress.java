package cc.ivera.parser.domain;

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

/**
 * 播放进度记忆：按用户 + 归一化 URL 哈希唯一，跨浏览器/设备续播。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("playback_progress")
public class PlaybackProgress {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sourceKey;
    private String sourceUrl;
    private String platform;
    private String title;
    private String formatId;
    private String mode;
    private Integer positionSeconds;
    private Integer durationSeconds;
    private Boolean completed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
