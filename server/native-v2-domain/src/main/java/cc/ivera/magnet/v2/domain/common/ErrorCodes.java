package cc.ivera.magnet.v2.domain.common;

/** 业务错误码 → HTTP 映射集中在 API 层 GlobalExceptionHandler */
public final class ErrorCodes {

    public static final String VALIDATION = "VALIDATION";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String REDIS_UNAVAILABLE = "REDIS_UNAVAILABLE";
    public static final String WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE";
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";

    public static final String INVALID_MAGNET = "INVALID_MAGNET";
    public static final String INVALID_TORRENT = "INVALID_TORRENT";
    public static final String INVALID_PATH = "INVALID_PATH";
    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String PRIVATE_TORRENT = "PRIVATE_TORRENT";
    public static final String METADATA_TIMEOUT = "METADATA_TIMEOUT";
    public static final String RESOLVE_FAILED = "RESOLVE_FAILED";
    public static final String INPUT_LOST = "INPUT_LOST";
    public static final String DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    public static final String EXPORT_IO_ERROR = "EXPORT_IO_ERROR";

    private ErrorCodes() {
    }
}
