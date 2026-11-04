package cc.ivera.magnet.v2.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

/** v2 API 的请求/响应 DTO；不复用持久化实体，避免泄漏内部字段。 */
public final class NativeV2Dtos {

    public record MagnetParseRequest(@NotBlank(message = "磁力链接不能为空") String magnetUri) {
    }

    public record TaskCreateRequest(@NotBlank(message = "解析任务标识不能为空") String parseUuid,
                                    @NotEmpty(message = "请至少选择一个文件") List<Integer> selectedFileIndexes) {
    }

    public record FileTicketRequest(int fileIndex) {
    }

    public record FileView(int fileIndex, String relativePath, long bytes) {
    }

    public record ParseSummary(String parseUuid, String status, String inputType,
                               String errorCode, String errorMessage, LocalDateTime createdAt) {
    }

    public record ParseDetailView(String parseUuid, String status, String inputType,
                                  String name, long totalBytes, String errorCode, String errorMessage,
                                  List<FileView> files) {
    }

    public record TaskFileView(int fileIndex, boolean selected, long declaredBytes,
                               long verifiedBytes, String state) {
    }

    public record TaskEventView(long sequence, String eventType, String payloadJson,
                                LocalDateTime createdAt) {
    }

    public record TaskView(String taskUuid, String status, long selectedBytes, long verifiedBytes,
                           double progressPercent, int retryCount, String errorCode, String errorMessage,
                           LocalDateTime createdAt, LocalDateTime updatedAt,
                           List<TaskFileView> files, List<TaskEventView> events) {
    }

    private NativeV2Dtos() {
    }
}
