package cc.ivera.magnet.v2.engine.libtorrent;

import java.nio.file.Path;

/** Native v2 Worker gRPC 连接配置；非回环地址必须启用 mTLS。 */
public final class NativeV2GrpcProperties {

    private final String host;
    private final int port;
    private final boolean tlsEnabled;
    private final Path caCertificatePath;
    private final Path clientCertificatePath;
    private final Path clientKeyPath;
    private final long rpcTimeoutMs;
    private final int maxMessageBytes;

    private NativeV2GrpcProperties(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.tlsEnabled = builder.tlsEnabled;
        this.caCertificatePath = builder.caCertificatePath;
        this.clientCertificatePath = builder.clientCertificatePath;
        this.clientKeyPath = builder.clientKeyPath;
        this.rpcTimeoutMs = builder.rpcTimeoutMs;
        this.maxMessageBytes = builder.maxMessageBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void validate() {
        if (host == null || host.isBlank()) {
            throw invalid("worker host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw invalid("worker port 必须在 1 到 65535 之间");
        }
        if (rpcTimeoutMs <= 0) {
            throw invalid("rpc-timeout-ms 必须大于 0");
        }
        if (maxMessageBytes <= 0) {
            throw invalid("grpc-max-message-bytes 必须大于 0");
        }
        if (tlsEnabled) {
            if (caCertificatePath == null || clientCertificatePath == null || clientKeyPath == null) {
                throw invalid("启用 TLS 时必须配置 CA 证书、客户端证书与客户端私钥");
            }
        } else if (!isLoopback(host)) {
            throw invalid("非回环地址的 Worker 必须启用 TLS");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Native v2 gRPC 配置无效：" + message);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public Path getCaCertificatePath() {
        return caCertificatePath;
    }

    public Path getClientCertificatePath() {
        return clientCertificatePath;
    }

    public Path getClientKeyPath() {
        return clientKeyPath;
    }

    public long getRpcTimeoutMs() {
        return rpcTimeoutMs;
    }

    public int getMaxMessageBytes() {
        return maxMessageBytes;
    }

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 50051;
        private boolean tlsEnabled;
        private Path caCertificatePath;
        private Path clientCertificatePath;
        private Path clientKeyPath;
        private long rpcTimeoutMs = 5000;
        private int maxMessageBytes = 16777216;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder tlsEnabled(boolean tlsEnabled) {
            this.tlsEnabled = tlsEnabled;
            return this;
        }

        public Builder caCertificatePath(Path caCertificatePath) {
            this.caCertificatePath = caCertificatePath;
            return this;
        }

        public Builder clientCertificatePath(Path clientCertificatePath) {
            this.clientCertificatePath = clientCertificatePath;
            return this;
        }

        public Builder clientKeyPath(Path clientKeyPath) {
            this.clientKeyPath = clientKeyPath;
            return this;
        }

        public Builder rpcTimeoutMs(long rpcTimeoutMs) {
            this.rpcTimeoutMs = rpcTimeoutMs;
            return this;
        }

        public Builder maxMessageBytes(int maxMessageBytes) {
            this.maxMessageBytes = maxMessageBytes;
            return this;
        }

        public NativeV2GrpcProperties build() {
            return new NativeV2GrpcProperties(this);
        }
    }
}
