package cc.ivera.magnet.v2.domain.task;

public final class TaskTransitions {

    private TaskTransitions() {
    }

    public static boolean canPause(TaskStatus status) {
        return status == TaskStatus.QUEUED
                || status == TaskStatus.STARTING
                || status == TaskStatus.DOWNLOADING
                || status == TaskStatus.VERIFYING
                || status == TaskStatus.RETRY_WAIT;
    }

    public static TaskStatus completionNext(TaskStatus current,
                                            boolean metadataReady,
                                            boolean recovering,
                                            boolean allVerified) {
        if (!metadataReady || recovering || !allVerified) {
            return TaskStatus.DOWNLOADING;
        }
        return current == TaskStatus.VERIFYING ? TaskStatus.COMPLETED : TaskStatus.VERIFYING;
    }
}
