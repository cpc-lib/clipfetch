package cc.ivera.ftp.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.ftp.application.FtpTaskService;
import cc.ivera.ftp.domain.FtpAuthMode;
import cc.ivera.ftp.domain.FtpProtocol;
import cc.ivera.ftp.interfaces.FtpDtos.ConnectionTestRequest;
import cc.ivera.ftp.interfaces.FtpDtos.ConnectionTestResponse;
import cc.ivera.ftp.interfaces.FtpDtos.CreateDirectoryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.CreateDirectoryResponse;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteBatchEntriesRequest;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteBatchEntriesResponse;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteEntryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteEntryResponse;
import cc.ivera.ftp.interfaces.FtpDtos.DirectoryListingResponse;
import cc.ivera.ftp.interfaces.FtpDtos.FileUploadResponse;
import cc.ivera.ftp.interfaces.FtpDtos.ListDirectoryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.MoveBatchEntriesRequest;
import cc.ivera.ftp.interfaces.FtpDtos.MoveBatchEntriesResponse;
import cc.ivera.ftp.interfaces.FtpDtos.StreamDownloadRequest;
import cc.ivera.ftp.interfaces.FtpDtos.TaskActionRequest;
import cc.ivera.ftp.interfaces.FtpDtos.TaskPageResponse;
import cc.ivera.ftp.interfaces.FtpDtos.TaskView;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ftp")
@RequiredArgsConstructor
public class FtpController {

    private final FtpTaskService taskService;

    @PostMapping("/connections/test")
    public ApiResponse<ConnectionTestResponse> testConnection(@Valid @RequestBody ConnectionTestRequest body,
                                                              HttpServletRequest request) {
        return ApiResponse.ok(taskService.testConnection(currentUserId(request), body));
    }

    @PostMapping("/directories/list")
    public ApiResponse<DirectoryListingResponse> listDirectory(@Valid @RequestBody ListDirectoryRequest body,
                                                               HttpServletRequest request) {
        return ApiResponse.ok(taskService.listDirectory(currentUserId(request), body));
    }

    @PostMapping("/directories/mkdir")
    public ApiResponse<CreateDirectoryResponse> createDirectory(@Valid @RequestBody CreateDirectoryRequest body,
                                                                HttpServletRequest request) {
        return ApiResponse.ok(taskService.createDirectory(currentUserId(request), body));
    }

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> uploadFile(
            @RequestParam(value = "file", required = false) List<MultipartFile> fileParts,
            @RequestParam(value = "files", required = false) List<MultipartFile> filesParts,
            @RequestParam("protocol") FtpProtocol protocol,
            @RequestParam("host") String host,
            @RequestParam(value = "port", defaultValue = "21") Integer port,
            @RequestParam(value = "remoteDirectory", defaultValue = "/") String remoteDirectory,
            @RequestParam(value = "remoteFileName", required = false) String remoteFileName,
            @RequestParam("authMode") FtpAuthMode authMode,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "password", required = false) String password,
            HttpServletRequest request) {
        List<MultipartFile> combined = new ArrayList<>();
        if (fileParts != null) {
            combined.addAll(fileParts);
        }
        if (filesParts != null) {
            combined.addAll(filesParts);
        }
        long userId = currentUserId(request);
        if (combined.size() == 1 && (filesParts == null || filesParts.isEmpty())) {
            return ApiResponse.ok(taskService.uploadFile(
                    userId, protocol, host, port, remoteDirectory,
                    remoteFileName, authMode, username, password, combined.get(0)));
        }
        return ApiResponse.ok(taskService.uploadFiles(
                userId, protocol, host, port, remoteDirectory,
                remoteFileName, authMode, username, password, combined));
    }

    @PostMapping("/entries/delete")
    public ApiResponse<DeleteEntryResponse> deleteEntry(@Valid @RequestBody DeleteEntryRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(taskService.deleteEntry(currentUserId(request), body));
    }

    @PostMapping("/entries/delete-batch")
    public ApiResponse<DeleteBatchEntriesResponse> deleteBatch(@Valid @RequestBody DeleteBatchEntriesRequest body,
                                                               HttpServletRequest request) {
        return ApiResponse.ok(taskService.deleteEntriesBatch(currentUserId(request), body));
    }

    @PostMapping("/entries/move-batch")
    public ApiResponse<MoveBatchEntriesResponse> moveBatch(@Valid @RequestBody MoveBatchEntriesRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(taskService.moveEntriesBatch(currentUserId(request), body));
    }

    @PostMapping("/files/stream-download")
    public void streamDownload(@Valid @RequestBody StreamDownloadRequest body,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        taskService.streamRemoteFile(currentUserId(request), body, response);
    }

    @GetMapping("/tasks")
    public ApiResponse<TaskPageResponse> list(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              HttpServletRequest request) {
        return ApiResponse.ok(taskService.list(currentUserId(request), page, size));
    }

    @GetMapping("/tasks/{taskUuid}")
    public ApiResponse<TaskView> get(@PathVariable String taskUuid, HttpServletRequest request) {
        return ApiResponse.ok(taskService.get(currentUserId(request), taskUuid));
    }

    @PostMapping("/tasks/{taskUuid}/pause")
    public ApiResponse<TaskView> pause(@PathVariable String taskUuid,
                                       @Valid @RequestBody TaskActionRequest body,
                                       HttpServletRequest request) {
        return ApiResponse.ok(taskService.pause(currentUserId(request), taskUuid, body.expectedRevision()));
    }

    @PostMapping("/tasks/{taskUuid}/resume")
    public ApiResponse<TaskView> resume(@PathVariable String taskUuid,
                                        @Valid @RequestBody TaskActionRequest body,
                                        HttpServletRequest request) {
        return ApiResponse.ok(taskService.resume(currentUserId(request), taskUuid, body.expectedRevision()));
    }

    @PostMapping("/tasks/{taskUuid}/restart")
    public ApiResponse<TaskView> restart(@PathVariable String taskUuid,
                                         @Valid @RequestBody TaskActionRequest body,
                                         HttpServletRequest request) {
        return ApiResponse.ok(taskService.restart(currentUserId(request), taskUuid, body.expectedRevision()));
    }

    @DeleteMapping("/tasks/{taskUuid}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String taskUuid,
                                                   @RequestParam long expectedRevision,
                                                   HttpServletRequest request) {
        taskService.delete(currentUserId(request), taskUuid, expectedRevision);
        return ApiResponse.ok(Map.of("deleted", true, "taskUuid", taskUuid));
    }

    static long currentUserId(HttpServletRequest request) {
        Object raw = request.getAttribute(AuthInterceptor.ATTR_USER);
        if (raw instanceof User user && user.getId() != null) {
            return user.getId();
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
