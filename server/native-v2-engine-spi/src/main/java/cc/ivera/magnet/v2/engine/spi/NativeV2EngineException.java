package cc.ivera.magnet.v2.engine.spi;

/** 引擎调用失败：携带稳定分类与是否可重试。 */
public class NativeV2EngineException extends RuntimeException {

    private final V2FailureKind kind;
    private final boolean retryable;

    public NativeV2EngineException(V2FailureKind kind, boolean retryable, String message) {
        super(message);
        this.kind = kind;
        this.retryable = retryable;
    }

    public NativeV2EngineException(V2FailureKind kind, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryable = retryable;
    }

    public V2FailureKind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }
}
