package cc.ivera.magnet.v2.scheduler;

import cc.ivera.magnet.v2.application.parse.NativeV2ParseDispatcher;
import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.application.task.NativeV2TaskDispatcher;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;

/** MySQL 作为可靠工作队列：固定延迟扫描到期的解析任务与下载任务。 */
public class NativeV2Scheduler {

    private final NativeV2ParseJobRepository parseJobs;
    private final NativeV2ParseDispatcher parseDispatcher;
    private final NativeV2TaskDispatcher taskDispatcher;
    private final int parseBatchSize;
    private final Clock clock;

    public NativeV2Scheduler(NativeV2ParseJobRepository parseJobs,
                             NativeV2ParseDispatcher parseDispatcher,
                             NativeV2TaskDispatcher taskDispatcher,
                             int parseBatchSize,
                             Clock clock) {
        if (parseBatchSize <= 0) {
            throw new IllegalArgumentException("parseBatchSize 必须为正数");
        }
        this.parseJobs = parseJobs;
        this.parseDispatcher = parseDispatcher;
        this.taskDispatcher = taskDispatcher;
        this.parseBatchSize = parseBatchSize;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.magnet.native-v2.parse-sweep-delay-ms}",
            initialDelayString = "${app.magnet.native-v2.parse-sweep-delay-ms}")
    public void sweepParses() {
        for (MagnetV2ParseJob job : parseJobs.findDue(LocalDateTime.now(clock), parseBatchSize)) {
            parseDispatcher.dispatchOne(job.getParseUuid());
        }
    }

    @Scheduled(
            fixedDelayString = "${app.magnet.native-v2.scheduler-delay-ms}",
            initialDelayString = "${app.magnet.native-v2.scheduler-delay-ms}")
    public void tickTasks() {
        taskDispatcher.tick();
    }
}
