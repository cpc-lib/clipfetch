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

@TableName("magnet_v2_task_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2TaskEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private Long sequence;
    private String eventType;
    private String payloadJson;
    private LocalDateTime createdAt;
}
