package cc.ivera.ftp.infrastructure;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.ftp")
public class FtpProperties {

    private boolean enabled;
    private boolean allowPlaintext;
    private boolean allowPublicTargets;
    private String allowedTargets = "";
    private String storageRoot = "downloads/ftp";
    @Min(1)
    private long maxFileBytes = 53687091200L;
    @Min(1)
    private int maxActiveTasksPerUser = 3;
    @Min(1)
    private int maxConcurrent = 4;
    @Min(1)
    private int maxConcurrentPerUser = 2;
    @Min(1)
    private int queueCapacity = 100;
    @Min(1)
    private int connectTimeoutMs = 5000;
    @Min(1)
    private int readTimeoutMs = 15000;
    @Min(1)
    private long transferIdleTimeoutMs = 30000L;
    @Min(1)
    private long transferDeadlineMs = 86400000L;
    @Min(0)
    private int retryMax = 3;
    @Min(1)
    private long retryInitialMs = 1000L;
    @Min(1)
    private long retryMaxMs = 15000L;
    @Min(1)
    private long checkpointBytes = 4194304L;
    @Min(1)
    private long checkpointIntervalMs = 2000L;
    @Min(4096)
    private int fileBufferBytes = 262144;
    private String credentialKeyId = "";
    private String credentialKey = "";
    private String truststorePath = "";
    private String truststorePassword = "";
    private String anonymousUsername = "anonymous";
    private String anonymousPassword = "clipfetch@localhost";
    @Min(1)
    private long connectionTestCooldownMs = 10000L;

    public boolean isConfigured() {
        return enabled
                && storageRoot != null && !storageRoot.isBlank()
                && credentialKeyId != null && !credentialKeyId.isBlank()
                && credentialKey != null && !credentialKey.isBlank();
    }
}
