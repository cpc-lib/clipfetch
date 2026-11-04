package cc.ivera.magnet.v2.domain.parse;

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

@TableName("magnet_v2_parse_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2ParseJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String parseUuid;
    private Long userId;
    private InputType inputType;
    private byte[] inputHash;
    private byte[] btih;
    private byte[] btmh;
    private String trackersJson;
    private String inputStorageKey;
    private Long resourceId;
    private ParseStatus status;
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
