package cc.ivera.ed2k.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ed2k_engine_action")
public class Ed2kEngineAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String actionUuid;
    private Long resourceId;
    private String action;
    private Long targetRevision;
    private String actionState;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String lastErrorCode;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
