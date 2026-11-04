package cc.ivera.magnet.v2.domain.parse;

/** 解析任务状态机 */
public enum ParseStatus {
    PENDING,
    RESOLVING,
    READY,
    FAILED,
    TIMEOUT
}
