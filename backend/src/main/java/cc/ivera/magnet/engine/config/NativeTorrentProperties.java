package cc.ivera.magnet.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Native BitTorrent is opt-in. It cannot be enabled for production tasks until
 * the peer transport and persistent resume implementation are complete.
 */
@ConfigurationProperties(prefix = "app.magnet.native-engine")
public class NativeTorrentProperties {
    private boolean enabled = false;
    private String storageRoot = "downloads/torrent-native";
    // Extra directories a user may pick as a native download root; empty means storageRoot only.
    private List<String> downloadRootBases = new ArrayList<>();
    private int maxMetadataBytes = 8 * 1024 * 1024;
    private int maxActiveTasksPerUser = 3;
    private int maxPeersPerTask = 20;
    private int maxPeersGlobal = 400;
    // Metadata-only HTTP endpoints are separately gated; download engine stays disabled.
    private boolean metadataLookupEnabled = false;
    // Experimental owner-scoped task controls: no download start endpoint.
    private boolean taskControlEnabled = false;
    // Separate explicit opt-in: verified .torrent -> CREATED only, no downloader.
    private boolean taskCreationEnabled = false;
    // Experimental on-demand worker, OFF even when task creation/control is enabled.
    private boolean workerRoundEnabled = false;
    // Explicit opt-in: DHT announces the verified public InfoHash to remote routers.
    private boolean dhtFallbackEnabled = false;
    // Bounded internal async Piece rounds; no automatic download entry point.
    private boolean asyncWorkerEnabled = false;
    // Opt-in P4.2 multi-round execution with bounded time and retries.
    private boolean continuousWorkerEnabled = false;
    private int continuousMaxPieces = 4;
    private int continuousMaxRounds = 64;
    private int continuousMaxSeconds = 600;
    private int continuousStalledRounds = 3;
    // P4.3 bounded parallel Piece network reads; default disabled.
    private boolean parallelPieceEnabled = false;
    // Explicit P4.4 opt-in, bounded Bitfield discovery before Piece scheduling.
    private boolean rarestFirstEnabled = false;
    // P4.4 round-local Peer TCP reuse; disabled until transport acceptance tests.
    private boolean peerConnectionReuseEnabled = false;
    // P4.5 per-round network deadline; applies to discovery and Piece transports.
    private int networkRoundBudgetMillis = 30000;
    private int bitfieldProbePeers = 4;
    private int bitfieldProbeTimeoutMillis = 1200;
    private int parallelPieceWorkers = 64;
    // Small opt-in group of durable Piece writes per checkpoint/DB CAS.
    private int commitGroupPieces = 256;
    // Require confirmed single JVM ownership before recovering orphaned RUNNING tasks.
    private boolean singleInstanceOrphanRecoveryEnabled = false;
    // Startup audit only stops expired retry leases; never resumes or dispatches downloads.
    private boolean startupRetryLeaseAuditEnabled = false;
    // Instance heartbeat is observational and opt-in; not a distributed fencing guarantee.
    private boolean instanceHeartbeatEnabled = false;
    // Temporary offline/test-only migration gate; never enabled by default.
    private boolean legacyWorkerCompatibilityEnabled = false;
    private int asyncWorkerThreads = 2;
    private int asyncWorkerQueueSize = 8;
    private int asyncWorkerPerUserLimit = 2;
    private int metadataConcurrentJobs = 2;
    private int metadataQueuedJobs = 8;
    private int metadataMaxPendingPerUser = 2;
    private int metadataTotalTimeoutSeconds = 90;
    private int metadataListenPort = 6881;
    // How many discovered Peers may be tried for the BEP9 info dict within one job.
    private int metadataPeerAttempts = 32;
    // Per-Peer ut_metadata exchange deadline.
    private int metadataPeerTimeoutMillis = 8000;
    // P4 explicit opt-in only. No default listener or firewall mutation.
    private boolean inboundTcpEnabled = false;
    // High-risk experimental multi-Piece TCP mailbox: default off.
    private boolean multiplexedPeerMailboxEnabled = false;
    private boolean inboundPublicExposureEnabled = false;
    private String inboundBindHost = "127.0.0.1";
    private int inboundListenPort = 6889;
    private int peerHandshakeTimeoutMillis = 2000;
    private int peerStallTimeoutMillis = 8000;
    private int peerConnectTimeoutMillis = 1500;
    private int peerMaxInFlightBlocks = 64;
    private int peerFailureBackoffMaxMillis = 30000;

    public int getPeerHandshakeTimeoutMillis() { return peerHandshakeTimeoutMillis; }
    public void setPeerHandshakeTimeoutMillis(int timeout) { this.peerHandshakeTimeoutMillis = timeout; }
    public int getPeerStallTimeoutMillis() { return peerStallTimeoutMillis; }
    public void setPeerStallTimeoutMillis(int timeout) { this.peerStallTimeoutMillis = timeout; }
    public int getPeerStallTimeoutSeconds() { return (int) Math.ceil(peerStallTimeoutMillis / 1000.0); }
    public void setPeerStallTimeoutSeconds(int seconds) { this.peerStallTimeoutMillis = seconds * 1000; }
    public int getPeerConnectTimeoutMillis() { return peerConnectTimeoutMillis; }
    public void setPeerConnectTimeoutMillis(int timeout) { this.peerConnectTimeoutMillis = timeout; }
    public int getPeerMaxInFlightBlocks() { return peerMaxInFlightBlocks; }
    public void setPeerMaxInFlightBlocks(int blocks) { peerMaxInFlightBlocks=blocks; }
    public int getPeerFailureBackoffMaxMillis() { return peerFailureBackoffMaxMillis; }
    public void setPeerFailureBackoffMaxMillis(int millis) { peerFailureBackoffMaxMillis=millis; }

    public boolean isMultiplexedPeerMailboxEnabled() {
        return multiplexedPeerMailboxEnabled;
    }
    public void setMultiplexedPeerMailboxEnabled(boolean enabled) {
        multiplexedPeerMailboxEnabled=enabled;
    }
    public boolean isInboundTcpEnabled() {return inboundTcpEnabled;}
    public void setInboundTcpEnabled(boolean flag){inboundTcpEnabled=flag;}
    public boolean isInboundPublicExposureEnabled(){return inboundPublicExposureEnabled;}
    public void setInboundPublicExposureEnabled(boolean flag){inboundPublicExposureEnabled=flag;}
    public String getInboundBindHost(){return inboundBindHost;}
    public void setInboundBindHost(String host){inboundBindHost=host;}
    public int getInboundListenPort(){return inboundListenPort;}
    public void setInboundListenPort(int port){inboundListenPort=port;}
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public List<String> getDownloadRootBases() { return downloadRootBases; }
    public void setDownloadRootBases(List<String> bases) {
        this.downloadRootBases = bases==null ? new ArrayList<>() : bases;
    }
    public int getMaxMetadataBytes() { return maxMetadataBytes; }
    public void setMaxMetadataBytes(int value) { this.maxMetadataBytes = value; }
    public int getMaxActiveTasksPerUser() { return maxActiveTasksPerUser; }
    public void setMaxActiveTasksPerUser(int value) { this.maxActiveTasksPerUser = value; }
    public int getMaxPeersPerTask() { return maxPeersPerTask; }
    public void setMaxPeersPerTask(int value) { this.maxPeersPerTask = value; }
    public int getMaxPeersGlobal() { return maxPeersGlobal; }
    public void setMaxPeersGlobal(int value) { this.maxPeersGlobal = value; }
    public int getNetworkRoundBudgetMillis() {return networkRoundBudgetMillis;}
    public void setNetworkRoundBudgetMillis(int millis) {networkRoundBudgetMillis=millis;}
    public int getNetworkRoundBudgetSeconds() {return (int) Math.ceil(networkRoundBudgetMillis / 1000.0);}
    public void setNetworkRoundBudgetSeconds(int seconds) {networkRoundBudgetMillis=seconds * 1000;}
    public boolean isPeerConnectionReuseEnabled() {return peerConnectionReuseEnabled;}
    public void setPeerConnectionReuseEnabled(boolean enabled) {peerConnectionReuseEnabled=enabled;}
    public boolean isRarestFirstEnabled() {return rarestFirstEnabled;}
    public void setRarestFirstEnabled(boolean enabled) {rarestFirstEnabled=enabled;}
    public int getBitfieldProbePeers() {return bitfieldProbePeers;}
    public void setBitfieldProbePeers(int count) {bitfieldProbePeers=count;}
    public int getBitfieldProbeTimeoutMillis() {return bitfieldProbeTimeoutMillis;}
    public void setBitfieldProbeTimeoutMillis(int ms) {bitfieldProbeTimeoutMillis=ms;}
    public boolean isParallelPieceEnabled() {return parallelPieceEnabled;}
    public void setParallelPieceEnabled(boolean enabled) {this.parallelPieceEnabled=enabled;}
    public int getCommitGroupPieces() {return commitGroupPieces;}
    public void setCommitGroupPieces(int pieces) {this.commitGroupPieces=pieces;}
    public int getParallelPieceWorkers() {return parallelPieceWorkers;}
    public void setParallelPieceWorkers(int workers) {this.parallelPieceWorkers=workers;}
    public boolean isContinuousWorkerEnabled() { return continuousWorkerEnabled; }
    public void setContinuousWorkerEnabled(boolean enabled) {this.continuousWorkerEnabled=enabled;}
    public int getContinuousMaxPieces() { return continuousMaxPieces; }
    public void setContinuousMaxPieces(int pieces) { this.continuousMaxPieces = pieces; }
    public int getContinuousMaxRounds() { return continuousMaxRounds; }
    public void setContinuousMaxRounds(int rounds) { this.continuousMaxRounds = rounds; }
    public int getContinuousMaxSeconds() { return continuousMaxSeconds; }
    public void setContinuousMaxSeconds(int seconds) { this.continuousMaxSeconds = seconds; }
    public int getContinuousStalledRounds() { return continuousStalledRounds; }
    public void setContinuousStalledRounds(int rounds) { this.continuousStalledRounds = rounds; }
    public boolean isAsyncWorkerEnabled() { return asyncWorkerEnabled; }
    public void setAsyncWorkerEnabled(boolean enabled) { this.asyncWorkerEnabled=enabled; }
    public boolean isLegacyWorkerCompatibilityEnabled() {return legacyWorkerCompatibilityEnabled;}
    public void setLegacyWorkerCompatibilityEnabled(boolean enabled) {legacyWorkerCompatibilityEnabled=enabled;}
    public boolean isInstanceHeartbeatEnabled() {return instanceHeartbeatEnabled;}
    public void setInstanceHeartbeatEnabled(boolean enabled) {instanceHeartbeatEnabled=enabled;}
    public boolean isStartupRetryLeaseAuditEnabled() {return startupRetryLeaseAuditEnabled;}
    public void setStartupRetryLeaseAuditEnabled(boolean enabled) {startupRetryLeaseAuditEnabled=enabled;}
    public boolean isSingleInstanceOrphanRecoveryEnabled() { return singleInstanceOrphanRecoveryEnabled; }
    public void setSingleInstanceOrphanRecoveryEnabled(boolean enabled) {
        this.singleInstanceOrphanRecoveryEnabled=enabled;
    }
    public int getAsyncWorkerThreads() { return asyncWorkerThreads; }
    public void setAsyncWorkerThreads(int threads) { this.asyncWorkerThreads=threads; }
    public int getAsyncWorkerQueueSize() { return asyncWorkerQueueSize; }
    public void setAsyncWorkerQueueSize(int size) { this.asyncWorkerQueueSize=size; }
    public int getAsyncWorkerPerUserLimit() { return asyncWorkerPerUserLimit; }
    public void setAsyncWorkerPerUserLimit(int limit) { this.asyncWorkerPerUserLimit=limit; }
    public boolean isDhtFallbackEnabled() { return dhtFallbackEnabled; }
    public void setDhtFallbackEnabled(boolean enabled) { this.dhtFallbackEnabled=enabled; }
    public boolean isWorkerRoundEnabled() { return workerRoundEnabled; }
    public void setWorkerRoundEnabled(boolean enabled) { this.workerRoundEnabled=enabled; }
    public boolean isTaskCreationEnabled() { return taskCreationEnabled; }
    public void setTaskCreationEnabled(boolean value) { this.taskCreationEnabled=value; }
    public boolean isTaskControlEnabled() { return taskControlEnabled; }
    public void setTaskControlEnabled(boolean value) { this.taskControlEnabled = value; }
    public boolean isMetadataLookupEnabled() { return metadataLookupEnabled; }
    public void setMetadataLookupEnabled(boolean enabled) { this.metadataLookupEnabled = enabled; }
    public int getMetadataConcurrentJobs() { return metadataConcurrentJobs; }
    public void setMetadataConcurrentJobs(int n) { this.metadataConcurrentJobs = n; }
    public int getMetadataQueuedJobs() { return metadataQueuedJobs; }
    public void setMetadataQueuedJobs(int n) { this.metadataQueuedJobs = n; }
    public int getMetadataMaxPendingPerUser() { return metadataMaxPendingPerUser; }
    public void setMetadataMaxPendingPerUser(int n) { this.metadataMaxPendingPerUser = n; }
    public int getMetadataTotalTimeoutSeconds() { return metadataTotalTimeoutSeconds; }
    public void setMetadataTotalTimeoutSeconds(int n) { this.metadataTotalTimeoutSeconds = n; }
    public int getMetadataListenPort() { return metadataListenPort; }
    public void setMetadataListenPort(int n) { this.metadataListenPort = n; }
    public int getMetadataPeerAttempts() { return metadataPeerAttempts; }
    public void setMetadataPeerAttempts(int n) { this.metadataPeerAttempts = n; }
    public int getMetadataPeerTimeoutMillis() { return metadataPeerTimeoutMillis; }
    public void setMetadataPeerTimeoutMillis(int n) { this.metadataPeerTimeoutMillis = n; }
}
