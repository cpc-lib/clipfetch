package cc.ivera.magnet.v2.application.task;

import cc.ivera.magnet.v2.domain.task.TaskStatus;
import cc.ivera.magnet.v2.domain.task.TaskTransitions;

/** 两轮完成：DOWNLOADING → VERIFYING → COMPLETED；恢复中不得完成。 */
public final class NativeV2CompletionPolicy {

    public TaskStatus next(TaskStatus current, boolean metadataReady, boolean recovering, boolean allVerified) {
        return TaskTransitions.completionNext(current, metadataReady, recovering, allVerified);
    }
}
