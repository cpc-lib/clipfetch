package cc.ivera.ed2k.infrastructure;

import cc.ivera.ed2k.domain.Ed2kIdentity;
import cc.ivera.ed2k.domain.Ed2kParsedLink;
import cc.ivera.ed2k.domain.Ed2kResourceState;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface Ed2kEngineClient {

    record EngineHealth(
            boolean configured,
            boolean connected,
            String version,
            String message,
            Boolean ed2kConnected,
            Boolean highId,
            Boolean kadConnected,
            Integer downloadSourceCount
    ) {
        public EngineHealth(boolean configured, boolean connected, String version, String message) {
            this(configured, connected, version, message, null, null, null, null);
        }
    }

    record AccelerationOutcome(
            boolean preferencesTuned,
            boolean networkConnectTriggered,
            boolean a4afSwapped,
            String message
    ) {
    }

    enum CommandOutcomeStatus {
        APPLIED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        UNKNOWN
    }

    record CommandOutcome(
            CommandOutcomeStatus status,
            String engineRef,
            String errorCode,
            String errorMessage,
            Long retryAfterMs
    ) {
        public static CommandOutcome applied(String engineRef) {
            return new CommandOutcome(CommandOutcomeStatus.APPLIED, engineRef, null, null, null);
        }

        public static CommandOutcome retryable(String errorCode, String errorMessage, Long retryAfterMs) {
            return new CommandOutcome(CommandOutcomeStatus.RETRYABLE_FAILURE, null, errorCode, errorMessage, retryAfterMs);
        }

        public static CommandOutcome permanent(String errorCode, String errorMessage) {
            return new CommandOutcome(CommandOutcomeStatus.PERMANENT_FAILURE, null, errorCode, errorMessage, null);
        }

        public static CommandOutcome unknown(String errorCode, String errorMessage) {
            return new CommandOutcome(CommandOutcomeStatus.UNKNOWN, null, errorCode, errorMessage, null);
        }
    }

    record EngineDownloadSnapshot(
            String md4Hash,
            long fileSize,
            String name,
            String rawStatus,
            Ed2kResourceState mappedState,
            long completedBytes,
            long speedBps,
            int sourcesTotal,
            int sourcesTransferring,
            String directory,
            boolean fromSharedList,
            boolean completedFlag,
            String errorCode,
            String errorMessage
    ) {
    }

    record CompletedFileCandidate(
            String md4Hash,
            long fileSize,
            String relativeFileName,
            boolean verifiedByUpstream
    ) {
    }

    EngineHealth checkHealth();

    Optional<EngineDownloadSnapshot> findByIdentity(Ed2kIdentity identity);

    Map<Ed2kIdentity, EngineDownloadSnapshot> listSnapshotsByIdentities(Collection<Ed2kIdentity> identities);

    CommandOutcome addDownload(Ed2kParsedLink link);

    CommandOutcome pauseDownload(Ed2kIdentity identity);

    CommandOutcome resumeDownload(Ed2kIdentity identity);

    default CommandOutcome boostDownload(Ed2kIdentity identity, boolean swapA4af) {
        return CommandOutcome.applied(identity.md4Hash());
    }

    default AccelerationOutcome accelerateEngine() {
        return new AccelerationOutcome(true, true, false, "已下发高速传输参数并触发 eD2k / Kad 双网重连寻源");
    }

    default void reloadSharedFiles() {
    }

    Optional<CompletedFileCandidate> inspectCompleted(Ed2kIdentity identity);
}
