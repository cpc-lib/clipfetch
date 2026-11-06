package cc.ivera.ftp.application;

import cc.ivera.ftp.domain.FtpDownloadTask;
import cc.ivera.ftp.domain.FtpDownloadTaskMapper;
import cc.ivera.ftp.domain.FtpTaskState;
import cc.ivera.ftp.infrastructure.FtpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FtpRecoveryService {

    private final FtpProperties properties;
    private final FtpDownloadTaskMapper taskMapper;
    private final FtpTransferCoordinator coordinator;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        if (!properties.isConfigured()) {
            return;
        }
        int limit = Math.max(1, properties.getQueueCapacity());
        long afterId = 0L;
        while (true) {
            List<FtpDownloadTask> batch = taskMapper.listRecoverable(limit, afterId);
            if (batch.isEmpty()) {
                return;
            }
            for (FtpDownloadTask task : batch) {
                afterId = Math.max(afterId, task.getId());
                FtpTaskState recovered = FtpTaskState.PAUSING.name().equals(task.getStatus())
                        || FtpTaskState.DELETING.name().equals(task.getStatus())
                        ? FtpTaskState.PAUSED : FtpTaskState.QUEUED;
                int updated = taskMapper.updateStateCas(task.getTaskUuid(), task.getUserId(), task.getRevision(),
                        recovered.name(), recovered == FtpTaskState.QUEUED ? "APP_RESTART_RECOVERY" : null,
                        recovered == FtpTaskState.QUEUED ? "服务重启后将从可靠检查点恢复 FTP 下载" : null);
                if (updated > 0 && recovered == FtpTaskState.QUEUED) {
                    try {
                        coordinator.submit(task.getUserId(), task.getTaskUuid());
                    } catch (RuntimeException ex) {
                        markQueueRejected(task);
                    }
                }
            }
            if (batch.size() < limit) {
                return;
            }
        }
    }

    private void markQueueRejected(FtpDownloadTask task) {
        FtpDownloadTask current = taskMapper.findOwnedByUuid(task.getTaskUuid(), task.getUserId());
        if (current != null) {
            taskMapper.updateStateCas(current.getTaskUuid(), current.getUserId(), current.getRevision(),
                    FtpTaskState.FAILED.name(), "QUEUE_FULL",
                    "服务恢复任务时 FTP 下载队列已满，请稍后手动继续");
        }
        log.warn("FTP recovery queue rejected for task {}", task.getTaskUuid());
    }
}
