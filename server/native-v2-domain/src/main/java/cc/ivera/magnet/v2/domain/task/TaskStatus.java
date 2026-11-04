package cc.ivera.magnet.v2.domain.task;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 用户任务状态机：
 * CREATED → QUEUED → STARTING → DOWNLOADING → VERIFYING → COMPLETED
 * QUEUED|STARTING|DOWNLOADING|VERIFYING|RETRY_WAIT → PAUSING → PAUSED → QUEUED
 * STARTING|DOWNLOADING|VERIFYING → RETRY_WAIT → QUEUED
 * ANY_NON_TERMINAL → FAILED；除 DELETED 外可进入 DELETING → DELETED
 * COMPLETED 完整性失败 → REPAIRING → QUEUED
 */
public enum TaskStatus {
    CREATED, QUEUED, STARTING, DOWNLOADING, VERIFYING, COMPLETED,
    PAUSING, PAUSED, RETRY_WAIT, REPAIRING, FAILED, DELETING, DELETED;

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(QUEUED, FAILED, DELETING)),
            Map.entry(QUEUED, EnumSet.of(STARTING, PAUSING, FAILED, DELETING)),
            Map.entry(STARTING, EnumSet.of(DOWNLOADING, PAUSING, RETRY_WAIT, FAILED, DELETING)),
            Map.entry(DOWNLOADING, EnumSet.of(VERIFYING, PAUSING, RETRY_WAIT, FAILED, DELETING)),
            Map.entry(VERIFYING, EnumSet.of(DOWNLOADING, COMPLETED, PAUSING, RETRY_WAIT, FAILED, DELETING)),
            Map.entry(COMPLETED, EnumSet.of(REPAIRING, DELETING)),
            Map.entry(PAUSING, EnumSet.of(PAUSED, FAILED, DELETING)),
            Map.entry(PAUSED, EnumSet.of(QUEUED, FAILED, DELETING)),
            Map.entry(RETRY_WAIT, EnumSet.of(QUEUED, PAUSING, FAILED, DELETING)),
            Map.entry(REPAIRING, EnumSet.of(QUEUED, FAILED, DELETING)),
            Map.entry(FAILED, EnumSet.of(RETRY_WAIT, DELETING)),
            Map.entry(DELETING, EnumSet.of(FAILED, DELETED)),
            Map.entry(DELETED, EnumSet.noneOf(TaskStatus.class))
    );

    public boolean canTransitionTo(TaskStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == DELETED;
    }

    public boolean contributesDemand() {
        return this == QUEUED || this == STARTING || this == DOWNLOADING || this == VERIFYING
                || this == RETRY_WAIT || this == REPAIRING;
    }
}
