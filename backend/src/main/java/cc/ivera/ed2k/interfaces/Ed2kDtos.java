package cc.ivera.ed2k.interfaces;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

public final class Ed2kDtos {

    private Ed2kDtos() {
    }

    public record EngineHealthResponse(
            boolean configured,
            boolean connected,
            String version,
            String message,
            Boolean ed2kConnected,
            Boolean highId,
            Boolean kadConnected,
            Integer downloadSourceCount
    ) {
        public EngineHealthResponse(boolean configured, boolean connected, String version, String message) {
            this(configured, connected, version, message, null, null, null, null);
        }
    }

    public record EngineAccelerationResponse(
            boolean preferencesTuned,
            boolean networkConnectTriggered,
            int boostedResources,
            String message
    ) {
    }

    public record CreateTaskRequest(
            @NotBlank(message = "请输入有效的 eD2k 下载链接")
            String link
    ) {
    }

    public record TaskActionRequest(
            Long expectedRevision
    ) {
    }

    public record TaskFileView(
            int index,
            String name,
            long sizeBytes,
            boolean downloadable
    ) {
    }

    public record TaskView(
            String taskUuid,
            String md4Hash,
            long fileSize,
            String requestedName,
            String desiredState,
            String taskState,
            String engineState,
            long progressBytes,
            double progressPercent,
            long speedBps,
            int sourcesTotal,
            int sourcesTransferring,
            long revision,
            boolean pendingEngine,
            boolean stale,
            boolean sharedDownloading,
            String statusHint,
            String errorCode,
            String errorMessage,
            boolean downloadable,
            List<TaskFileView> files,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt
    ) {
    }

    public record TaskPageResponse(
            List<TaskView> items,
            int page,
            int size,
            int total,
            boolean engineConnected,
            boolean stale
    ) {
    }
}
