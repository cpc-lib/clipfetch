package cc.ivera.ftp.domain;

public enum FtpTaskState {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSING,
    PAUSED,
    DELETING,
    RETRY_WAIT,
    VERIFYING,
    COMPLETED,
    FAILED,
    NEEDS_RESTART,
    NEEDS_VERIFICATION,
    BLOCKED_CREDENTIALS
}
