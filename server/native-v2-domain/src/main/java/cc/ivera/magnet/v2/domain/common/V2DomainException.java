package cc.ivera.magnet.v2.domain.common;

/** 领域异常：code 由 API 层映射为 HTTP 状态码 */
public class V2DomainException extends RuntimeException {

    private final String code;

    public V2DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
