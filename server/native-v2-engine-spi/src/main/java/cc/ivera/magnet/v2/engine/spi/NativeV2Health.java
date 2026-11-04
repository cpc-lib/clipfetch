package cc.ivera.magnet.v2.engine.spi;

/** 引擎健康状态：离线或异常只降级字段，不抛异常。 */
public record NativeV2Health(boolean connected, String version, String message) {

    public NativeV2Health {
        version = version == null ? "" : version;
        message = message == null ? "" : message;
    }

    public static NativeV2Health available(String version) {
        return new NativeV2Health(true, version, "自研引擎 v2 已连接");
    }

    public static NativeV2Health unavailable(String message) {
        return new NativeV2Health(false, "", message);
    }
}
