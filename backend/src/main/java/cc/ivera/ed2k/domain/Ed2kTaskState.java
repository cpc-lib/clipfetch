package cc.ivera.ed2k.domain;

public enum Ed2kTaskState {
    QUEUED,
    WAITING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    ERROR
}
