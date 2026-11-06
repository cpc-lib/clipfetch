package cc.ivera.magnet.v2.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.magnet.native-v2")
public class NativeV2Properties {

    private boolean enabled;
    private String storageRoot = "downloads/magnet-v2";
    private String workerHost = "127.0.0.1";
    private int workerPort = 50051;
    private boolean workerTlsEnabled;
    private String workerCaCert = "";
    private String workerClientCert = "";
    private String workerClientKey = "";
    private long rpcTimeoutMs = 5000;
    private int grpcMaxMessageBytes = 16777216;
    private long leaseDurationMs = 30000;
    private long metadataTimeoutMs = 90000;
    private long metadataPollMs = 1000;
    private long resumeSaveTimeoutMs = 10000;
    private long recheckTimeoutMs = 600000;
    private long schedulerDelayMs = 1000;
    private long parseSweepDelayMs = 1000;
    private int parseRatePerMinute = 10;
    private int parseConcurrentPerUser = 2;
    private long maxTorrentBytes = 8388608;
    private int maxTrackers = 64;
    private int maxFiles = 100000;
    private int maxSelectedFiles = 10000;
    private long maxTotalBytes = 1099511627776L;
    private int maxActiveTasksPerUser = 3;
    private int workerRpcConcurrency = 8;
    private String workerId = "local-worker";
    private String workerListenAddress = "0.0.0.0";
    private int workerDownloadPort = 6881;
    private boolean workerDhtEnabled = true;
    private int workerMaxConnections = 200;
    private int workerMaxUploadSlots = 8;
    private long workerDownloadRateLimitBps;
    private long workerUploadRateLimitBps;
    private int workerAlertQueueSize = 10000;
    private int workerDiskWriteBufferBytes = 4194304;
    private int retryMaxAttempts = 5;
    private long retryInitialMs = 1000;
    private long retryMaxMs = 60000;
    private int retryJitterPercent = 20;
    private long ticketTtlSeconds = 300;
    private int ticketMaxActive = 100;
    private int ticketRatePerMinute = 30;
    private boolean blockPrivateNetworks = true;
    private String redisHost = "127.0.0.1";
    private int redisPort = 6379;
    private String redisPassword = "";
    private int redisDatabase;
    private long redisConnectTimeoutMs = 2000;

    public void validate(String datasourceUrl) {
        if (!enabled) {
            return;
        }
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:mysql:")) {
            throw invalid("启用 Native v2 时必须使用 MySQL 数据源");
        }
        requireText(storageRoot, "storage-root");
        requireText(workerHost, "worker-host");
        requireText(workerId, "worker-id");
        requireText(workerListenAddress, "worker-listen-address");
        requireText(redisHost, "redis-host");
        requirePort(workerPort, "worker-port");
        requirePort(workerDownloadPort, "worker-download-port");
        requirePort(redisPort, "redis-port");
        if (workerTlsEnabled) {
            requireText(workerCaCert, "worker-ca-cert");
            requireText(workerClientCert, "worker-client-cert");
            requireText(workerClientKey, "worker-client-key");
        }
        requirePositive(rpcTimeoutMs, "rpc-timeout-ms");
        requirePositive(grpcMaxMessageBytes, "grpc-max-message-bytes");
        requirePositive(leaseDurationMs, "lease-duration-ms");
        requirePositive(metadataTimeoutMs, "metadata-timeout-ms");
        requirePositive(metadataPollMs, "metadata-poll-ms");
        requirePositive(resumeSaveTimeoutMs, "resume-save-timeout-ms");
        requirePositive(recheckTimeoutMs, "recheck-timeout-ms");
        requirePositive(schedulerDelayMs, "scheduler-delay-ms");
        requirePositive(parseSweepDelayMs, "parse-sweep-delay-ms");
        requirePositive(parseRatePerMinute, "parse-rate-per-minute");
        requirePositive(parseConcurrentPerUser, "parse-concurrent-per-user");
        requirePositive(maxTorrentBytes, "max-torrent-bytes");
        requirePositive(maxTrackers, "max-trackers");
        requirePositive(maxFiles, "max-files");
        requirePositive(maxSelectedFiles, "max-selected-files");
        requirePositive(maxTotalBytes, "max-total-bytes");
        requirePositive(maxActiveTasksPerUser, "max-active-tasks-per-user");
        requirePositive(workerRpcConcurrency, "worker-rpc-concurrency");
        requirePositive(workerMaxConnections, "worker-max-connections");
        requirePositive(workerMaxUploadSlots, "worker-max-upload-slots");
        requireNonNegative(workerDownloadRateLimitBps, "worker-download-rate-limit-bps");
        requireNonNegative(workerUploadRateLimitBps, "worker-upload-rate-limit-bps");
        requirePositive(workerAlertQueueSize, "worker-alert-queue-size");
        requirePositive(workerDiskWriteBufferBytes, "worker-disk-write-buffer-bytes");
        requirePositive(retryMaxAttempts, "retry-max-attempts");
        requirePositive(retryInitialMs, "retry-initial-ms");
        requirePositive(retryMaxMs, "retry-max-ms");
        if (retryJitterPercent < 0 || retryJitterPercent > 100) {
            throw invalid("retry-jitter-percent 必须在 0 到 100 之间");
        }
        requirePositive(ticketTtlSeconds, "ticket-ttl-seconds");
        requirePositive(ticketMaxActive, "ticket-max-active");
        requirePositive(ticketRatePerMinute, "ticket-rate-per-minute");
        requireNonNegative(redisDatabase, "redis-database");
        requirePositive(redisConnectTimeoutMs, "redis-connect-timeout-ms");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " 不能为空");
        }
    }

    private static void requirePort(int value, String name) {
        if (value < 1 || value > 65535) {
            throw invalid(name + " 必须在 1 到 65535 之间");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw invalid(name + " 必须大于 0");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw invalid(name + " 不能小于 0");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Native v2 配置无效：" + message);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public String getWorkerHost() { return workerHost; }
    public void setWorkerHost(String workerHost) { this.workerHost = workerHost; }
    public int getWorkerPort() { return workerPort; }
    public void setWorkerPort(int workerPort) { this.workerPort = workerPort; }
    public boolean isWorkerTlsEnabled() { return workerTlsEnabled; }
    public void setWorkerTlsEnabled(boolean workerTlsEnabled) { this.workerTlsEnabled = workerTlsEnabled; }
    public String getWorkerCaCert() { return workerCaCert; }
    public void setWorkerCaCert(String workerCaCert) { this.workerCaCert = workerCaCert; }
    public String getWorkerClientCert() { return workerClientCert; }
    public void setWorkerClientCert(String workerClientCert) { this.workerClientCert = workerClientCert; }
    public String getWorkerClientKey() { return workerClientKey; }
    public void setWorkerClientKey(String workerClientKey) { this.workerClientKey = workerClientKey; }
    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
    public void setRpcTimeoutMs(long rpcTimeoutMs) { this.rpcTimeoutMs = rpcTimeoutMs; }
    public int getGrpcMaxMessageBytes() { return grpcMaxMessageBytes; }
    public void setGrpcMaxMessageBytes(int grpcMaxMessageBytes) { this.grpcMaxMessageBytes = grpcMaxMessageBytes; }
    public long getLeaseDurationMs() { return leaseDurationMs; }
    public void setLeaseDurationMs(long leaseDurationMs) { this.leaseDurationMs = leaseDurationMs; }
    public long getMetadataTimeoutMs() { return metadataTimeoutMs; }
    public void setMetadataTimeoutMs(long metadataTimeoutMs) { this.metadataTimeoutMs = metadataTimeoutMs; }
    public long getMetadataPollMs() { return metadataPollMs; }
    public void setMetadataPollMs(long metadataPollMs) { this.metadataPollMs = metadataPollMs; }
    public long getResumeSaveTimeoutMs() { return resumeSaveTimeoutMs; }
    public void setResumeSaveTimeoutMs(long resumeSaveTimeoutMs) { this.resumeSaveTimeoutMs = resumeSaveTimeoutMs; }
    public long getRecheckTimeoutMs() { return recheckTimeoutMs; }
    public void setRecheckTimeoutMs(long recheckTimeoutMs) { this.recheckTimeoutMs = recheckTimeoutMs; }
    public long getSchedulerDelayMs() { return schedulerDelayMs; }
    public void setSchedulerDelayMs(long schedulerDelayMs) { this.schedulerDelayMs = schedulerDelayMs; }
    public long getParseSweepDelayMs() { return parseSweepDelayMs; }
    public void setParseSweepDelayMs(long parseSweepDelayMs) { this.parseSweepDelayMs = parseSweepDelayMs; }
    public int getParseRatePerMinute() { return parseRatePerMinute; }
    public void setParseRatePerMinute(int parseRatePerMinute) { this.parseRatePerMinute = parseRatePerMinute; }
    public int getParseConcurrentPerUser() { return parseConcurrentPerUser; }
    public void setParseConcurrentPerUser(int parseConcurrentPerUser) { this.parseConcurrentPerUser = parseConcurrentPerUser; }
    public long getMaxTorrentBytes() { return maxTorrentBytes; }
    public void setMaxTorrentBytes(long maxTorrentBytes) { this.maxTorrentBytes = maxTorrentBytes; }
    public int getMaxTrackers() { return maxTrackers; }
    public void setMaxTrackers(int maxTrackers) { this.maxTrackers = maxTrackers; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public int getMaxSelectedFiles() { return maxSelectedFiles; }
    public void setMaxSelectedFiles(int maxSelectedFiles) { this.maxSelectedFiles = maxSelectedFiles; }
    public long getMaxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(long maxTotalBytes) { this.maxTotalBytes = maxTotalBytes; }
    public int getMaxActiveTasksPerUser() { return maxActiveTasksPerUser; }
    public void setMaxActiveTasksPerUser(int maxActiveTasksPerUser) { this.maxActiveTasksPerUser = maxActiveTasksPerUser; }
    public int getWorkerRpcConcurrency() { return workerRpcConcurrency; }
    public void setWorkerRpcConcurrency(int workerRpcConcurrency) { this.workerRpcConcurrency = workerRpcConcurrency; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerListenAddress() { return workerListenAddress; }
    public void setWorkerListenAddress(String workerListenAddress) { this.workerListenAddress = workerListenAddress; }
    public int getWorkerDownloadPort() { return workerDownloadPort; }
    public void setWorkerDownloadPort(int workerDownloadPort) { this.workerDownloadPort = workerDownloadPort; }
    public boolean isWorkerDhtEnabled() { return workerDhtEnabled; }
    public void setWorkerDhtEnabled(boolean workerDhtEnabled) { this.workerDhtEnabled = workerDhtEnabled; }
    public int getWorkerMaxConnections() { return workerMaxConnections; }
    public void setWorkerMaxConnections(int workerMaxConnections) { this.workerMaxConnections = workerMaxConnections; }
    public int getWorkerMaxUploadSlots() { return workerMaxUploadSlots; }
    public void setWorkerMaxUploadSlots(int workerMaxUploadSlots) { this.workerMaxUploadSlots = workerMaxUploadSlots; }
    public long getWorkerDownloadRateLimitBps() { return workerDownloadRateLimitBps; }
    public void setWorkerDownloadRateLimitBps(long workerDownloadRateLimitBps) { this.workerDownloadRateLimitBps = workerDownloadRateLimitBps; }
    public long getWorkerUploadRateLimitBps() { return workerUploadRateLimitBps; }
    public void setWorkerUploadRateLimitBps(long workerUploadRateLimitBps) { this.workerUploadRateLimitBps = workerUploadRateLimitBps; }
    public int getWorkerAlertQueueSize() { return workerAlertQueueSize; }
    public void setWorkerAlertQueueSize(int workerAlertQueueSize) { this.workerAlertQueueSize = workerAlertQueueSize; }
    public int getWorkerDiskWriteBufferBytes() { return workerDiskWriteBufferBytes; }
    public void setWorkerDiskWriteBufferBytes(int workerDiskWriteBufferBytes) { this.workerDiskWriteBufferBytes = workerDiskWriteBufferBytes; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
    public long getRetryInitialMs() { return retryInitialMs; }
    public void setRetryInitialMs(long retryInitialMs) { this.retryInitialMs = retryInitialMs; }
    public long getRetryMaxMs() { return retryMaxMs; }
    public void setRetryMaxMs(long retryMaxMs) { this.retryMaxMs = retryMaxMs; }
    public int getRetryJitterPercent() { return retryJitterPercent; }
    public void setRetryJitterPercent(int retryJitterPercent) { this.retryJitterPercent = retryJitterPercent; }
    public long getTicketTtlSeconds() { return ticketTtlSeconds; }
    public void setTicketTtlSeconds(long ticketTtlSeconds) { this.ticketTtlSeconds = ticketTtlSeconds; }
    public int getTicketMaxActive() { return ticketMaxActive; }
    public void setTicketMaxActive(int ticketMaxActive) { this.ticketMaxActive = ticketMaxActive; }
    public int getTicketRatePerMinute() { return ticketRatePerMinute; }
    public void setTicketRatePerMinute(int ticketRatePerMinute) { this.ticketRatePerMinute = ticketRatePerMinute; }
    public boolean isBlockPrivateNetworks() { return blockPrivateNetworks; }
    public void setBlockPrivateNetworks(boolean blockPrivateNetworks) { this.blockPrivateNetworks = blockPrivateNetworks; }
    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }
    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }
    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String redisPassword) { this.redisPassword = redisPassword; }
    public int getRedisDatabase() { return redisDatabase; }
    public void setRedisDatabase(int redisDatabase) { this.redisDatabase = redisDatabase; }
    public long getRedisConnectTimeoutMs() { return redisConnectTimeoutMs; }
    public void setRedisConnectTimeoutMs(long redisConnectTimeoutMs) { this.redisConnectTimeoutMs = redisConnectTimeoutMs; }
}
