package cc.ivera.magnet.v2.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("magnet_v2_engine_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2EngineSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resourceId;
    private String engineSessionId;
    private String workerId;
    private String state;
    private Long activeTaskId;
    private Long ownerUserId;
    private Long ownerEpoch;
    private Long resumeVersion;
    private String resumeKey;
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
