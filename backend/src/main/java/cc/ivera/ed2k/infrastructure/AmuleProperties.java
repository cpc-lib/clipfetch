package cc.ivera.ed2k.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ed2k")
public class AmuleProperties {

    private boolean enabled = false;
    private String apiBaseUrl = "http://127.0.0.1:4711";
    private String apiPassword = "";
    private String dataRoot = "downloads/ed2k";
    private String configRoot = "infra/amule/data";
    private String incomingRoot = "downloads/ed2k/incoming";
    private int apiHostPort = 4711;
    private int ed2kTcpPort = 4662;
    private int ed2kServerUdpPort = 4665;
    private int kadUdpPort = 4672;
    private int httpConnectTimeoutMs = 3000;
    private int httpRequestTimeoutMs = 8000;
    private int httpRetryMax = 3;
    private long httpRetryInitialMs = 1000L;
    private long httpRetryMaxMs = 15000L;
    private int maxLinkLength = 4096;
    private long maxFileBytes = 53687091200L;
    private int maxActiveTasksPerUser = 3;
    private int reconcileBatchSize = 20;
    private long reconcileCooldownMs = 2000L;
    private long observationStaleMs = 5000L;
    private int fileBufferBytes = 262144;
    private String downloadPriority = "high";
    private boolean enableA4afAuto = true;
    private boolean autoConnectNetworks = true;
    private boolean tunePreferencesOnStartup = true;
    private int maxDownloadKbps = 0;
    private int maxUploadKbps = 0;
    private int uploadSlotMinKbps = 10;
    private int maxConnections = 800;
    private int maxSourcesPerFile = 600;
    private int maxNewConnectionsPer5s = 60;
    private int kadMaxSourceSearches = 25;
    private int engineFileBufferBytes = 1500000;
    private boolean obfuscationEnabled = true;
    private boolean endgameEnabled = true;
    private boolean upnpEnabled = false;

    public String normalizedDownloadPriority() {
        if (downloadPriority == null || downloadPriority.isBlank()) {
            return "high";
        }
        String p = downloadPriority.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (p) {
            case "low", "normal", "high", "auto" -> p;
            default -> "high";
        };
    }

    public int normalizedEngineFileBufferBytes() {
        int clamped = Math.max(0, Math.min(3825000, engineFileBufferBytes));
        return (clamped / 15000) * 15000;
    }

    public int normalizedKadMaxSourceSearches() {
        return Math.max(5, Math.min(50, kadMaxSourceSearches));
    }

    public int normalizedUploadSlotMinKbps() {
        return Math.max(1, Math.min(65535, uploadSlotMinKbps));
    }

    /**
     * aMule 核心在 1..9 KiB/s 上传限速时会强制触发 3x/4x 下载限速；
     * 若用户配置在 1..9 之间，自动抬升至 10 KiB/s 以解除反吸血下载限速（0 表示不限速）。
     */
    public int normalizedMaxUploadKbps() {
        if (maxUploadKbps <= 0) {
            return 0;
        }
        return Math.max(10, Math.min(65535, maxUploadKbps));
    }

    public int normalizedMaxDownloadKbps() {
        return Math.max(0, Math.min(65535, maxDownloadKbps));
    }

    public boolean isConfigured() {
        return enabled
                && apiBaseUrl != null && !apiBaseUrl.isBlank()
                && apiPassword != null && !apiPassword.isBlank();
    }
}
