package cc.ivera.magnet.domain;

import lombok.*;
import java.time.LocalDateTime;

/** Owner-scoped durable scheduling intent, not authorization to start I/O. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TorrentNativeRetryPlan {
    private Long id;
    private String taskUuid;
    private Long userId;
    private Long expectedRevision;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String workerJobUuid;
    private String workerOutcome;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime updatedAt;
}
