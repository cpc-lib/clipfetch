package cc.ivera.ftp.interfaces;

import cc.ivera.ftp.domain.FtpAuthMode;
import cc.ivera.ftp.domain.FtpProtocol;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class FtpDtos {

    private FtpDtos() {
    }

    public record CreateTaskRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotBlank(message = "请输入远端文件路径") String remotePath,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password,
            String displayName,
            String expectedSha256
    ) {
    }

    public record BatchCreateTaskItem(
            @NotBlank(message = "请输入远端文件路径") String remotePath,
            String displayName,
            String expectedSha256
    ) {
    }

    public record CreateBatchTasksRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password,
            @NotEmpty(message = "请至少选择一个待下载的远端文件") List<BatchCreateTaskItem> items
    ) {
    }

    public record ConnectionTestRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            String remotePath,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password,
            String displayName,
            String expectedSha256
    ) {
    }

    public record TaskActionRequest(
            @NotNull(message = "缺少任务版本，请刷新后重试") Long expectedRevision
    ) {
    }

    public record EngineHealthResponse(
            boolean configured,
            boolean connected,
            String version,
            String message
    ) {
    }

    public record ConnectionTestResponse(
            boolean connected,
            Long remoteSize,
            String remoteModifiedAt,
            String message
    ) {
    }

    public record TaskView(
            String taskUuid,
            String protocol,
            String host,
            int port,
            String displayName,
            String status,
            Long totalBytes,
            long downloadedBytes,
            double progressPercent,
            long revision,
            int retryCount,
            String errorCode,
            String errorMessage,
            boolean downloadable,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt
    ) {
    }

    public record TaskPageResponse(
            List<TaskView> items,
            int page,
            int size,
            int total
    ) {
    }

    public record ListDirectoryRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            String remotePath,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record RemoteEntryView(
            String name,
            String path,
            String type,
            Long size,
            String modifiedAt
    ) {
    }

    public record DirectoryListingResponse(
            String currentPath,
            String parentPath,
            List<RemoteEntryView> entries
    ) {
    }

    public record CreateDirectoryRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            String parentPath,
            @NotBlank(message = "请输入新建文件夹名称") String directoryName,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record CreateDirectoryResponse(
            String createdPath,
            String directoryName,
            String message
    ) {
    }

    public record UploadedFileItem(
            String remotePath,
            String fileName,
            long uploadedBytes
    ) {
    }

    public record FileUploadResponse(
            String remotePath,
            String fileName,
            long uploadedBytes,
            String message,
            int uploadedCount,
            List<UploadedFileItem> files
    ) {
        public FileUploadResponse(String remotePath, String fileName, long uploadedBytes, String message) {
            this(
                    remotePath,
                    fileName,
                    uploadedBytes,
                    message,
                    1,
                    List.of(new UploadedFileItem(remotePath, fileName, uploadedBytes))
            );
        }
    }

    public record DeleteEntryRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotBlank(message = "请选择要删除的远端条目") String remotePath,
            @NotBlank(message = "请指定条目类型") String entryType,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record DeleteEntryResponse(
            String deletedPath,
            String entryName,
            String message
    ) {
    }

    public record DeleteBatchItem(
            @NotBlank(message = "请选择要删除的远端条目") String remotePath,
            @NotBlank(message = "请指定条目类型") String entryType
    ) {
    }

    public record DeleteBatchEntriesRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password,
            @NotEmpty(message = "请至少选择一个待删除的远端条目") List<DeleteBatchItem> items
    ) {
    }

    public record DeleteBatchEntriesResponse(
            int deletedCount,
            List<String> deletedPaths,
            String message
    ) {
    }

    public record StreamDownloadRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotBlank(message = "请输入远端文件路径") String remotePath,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record MoveBatchEntriesRequest(
            @NotNull(message = "请选择 FTP/FTPS 协议") FtpProtocol protocol,
            @NotBlank(message = "请输入 FTP 服务器主机名") String host,
            @Min(value = 1, message = "FTP 端口必须在 1 到 65535 之间")
            @Max(value = 65535, message = "FTP 端口必须在 1 到 65535 之间") Integer port,
            @NotBlank(message = "请输入目标远端目录") String targetDirectory,
            @NotNull(message = "请选择 FTP 认证方式") FtpAuthMode authMode,
            String username,
            String password,
            @NotEmpty(message = "请至少选择一个待移动的远端条目") List<String> sourcePaths
    ) {
    }

    public record MovedEntryItem(
            String sourcePath,
            String targetPath,
            String fileName
    ) {
    }

    public record MoveBatchEntriesResponse(
            int movedCount,
            String targetDirectory,
            List<MovedEntryItem> movedEntries,
            String message
    ) {
    }
}
