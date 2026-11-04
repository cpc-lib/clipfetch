package cc.ivera.magnet.v2.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.v2.application.parse.NativeV2ParseService;
import cc.ivera.magnet.v2.application.task.NativeV2TaskService;
import cc.ivera.magnet.v2.application.task.TaskDetail;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import cc.ivera.magnet.v2.domain.task.MagnetV2TaskEvent;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.FileTicketRequest;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.FileView;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.MagnetParseRequest;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.ParseDetailView;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.ParseSummary;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.TaskCreateRequest;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.TaskEventView;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.TaskFileView;
import cc.ivera.magnet.v2.interfaces.NativeV2Dtos.TaskView;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Native v2 解析与下载任务 API；所有端点按 user_id 隔离。 */
@RestController
@RequestMapping("/api/magnet/v2")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.magnet.native-v2.enabled", havingValue = "true")
public class NativeV2Controller {

    private static final int MAX_PAGE_SIZE = 30;

    private final NativeV2ParseService parseService;
    private final NativeV2TaskService taskService;

    @PostMapping("/parses")
    public ApiResponse<ParseSummary> submitMagnet(HttpServletRequest request,
                                                  @Valid @RequestBody MagnetParseRequest body) {
        return ApiResponse.ok(summary(parseService.submitMagnet(currentUserId(request), body.magnetUri())));
    }

    @PostMapping(value = "/parses/torrent", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ParseSummary> submitTorrent(HttpServletRequest request,
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要解析的种子文件（.torrent）");
        }
        return ApiResponse.ok(summary(parseService.submitTorrent(currentUserId(request),
                file.getOriginalFilename(), file.getBytes())));
    }

    @GetMapping("/parses/{parseUuid}")
    public ApiResponse<ParseDetailView> parseDetail(HttpServletRequest request,
                                                    @PathVariable String parseUuid) {
        NativeV2ParseService.ParseDetail detail =
                parseService.detail(currentUserId(request), parseUuid);
        List<FileView> files = detail.files().stream()
                .map(file -> new FileView(file.fileIndex(), file.relativePath(), file.bytes()))
                .toList();
        MagnetV2ParseJob job = detail.job();
        return ApiResponse.ok(new ParseDetailView(job.getParseUuid(), job.getStatus().name(),
                job.getInputType().name(), detail.name(), detail.totalBytes(),
                job.getErrorCode(), job.getErrorMessage(), files));
    }

    @GetMapping("/parses")
    public ApiResponse<List<ParseSummary>> listParses(HttpServletRequest request,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(parseService.listOwned(currentUserId(request), page, safeSize(size)).stream()
                .map(NativeV2Controller::summary)
                .toList());
    }

    @PostMapping("/tasks")
    public ApiResponse<TaskView> createTask(HttpServletRequest request,
                                            @Valid @RequestBody TaskCreateRequest body) {
        MagnetV2DownloadTask task = taskService.create(currentUserId(request), body.parseUuid(),
                body.selectedFileIndexes());
        return ApiResponse.ok(taskView(taskService.detail(currentUserId(request), task.getTaskUuid())));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskView>> listTasks(HttpServletRequest request,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.listOwned(currentUserId(request), page, safeSize(size)).stream()
                .map(NativeV2Controller::taskView)
                .toList());
    }

    @GetMapping("/tasks/{taskUuid}")
    public ApiResponse<TaskView> taskDetail(HttpServletRequest request,
                                            @PathVariable String taskUuid) {
        return ApiResponse.ok(taskView(taskService.detail(currentUserId(request), taskUuid)));
    }

    @PostMapping("/tasks/{taskUuid}/pause")
    public ApiResponse<TaskView> pause(HttpServletRequest request, @PathVariable String taskUuid) {
        taskService.pause(currentUserId(request), taskUuid);
        return ApiResponse.ok(taskView(taskService.detail(currentUserId(request), taskUuid)));
    }

    @PostMapping("/tasks/{taskUuid}/resume")
    public ApiResponse<TaskView> resume(HttpServletRequest request, @PathVariable String taskUuid) {
        taskService.resume(currentUserId(request), taskUuid);
        return ApiResponse.ok(taskView(taskService.detail(currentUserId(request), taskUuid)));
    }

    @PostMapping("/tasks/{taskUuid}/file-ticket")
    public ApiResponse<Map<String, String>> fileTicket(HttpServletRequest request,
                                                       @PathVariable String taskUuid,
                                                       @RequestBody FileTicketRequest body) {
        String token = taskService.issueTicket(currentUserId(request), taskUuid, body.fileIndex());
        return ApiResponse.ok(Map.of("url", "/api/magnet/v2/downloads/stream/" + token));
    }

    private static ParseSummary summary(MagnetV2ParseJob job) {
        return new ParseSummary(job.getParseUuid(), job.getStatus().name(), job.getInputType().name(),
                job.getErrorCode(), job.getErrorMessage(), job.getCreatedAt());
    }

    private static TaskView taskView(TaskDetail detail) {
        return taskView(detail.task(), detail.files(), detail.events());
    }

    private static TaskView taskView(MagnetV2DownloadTask task) {
        return new TaskView(task.getTaskUuid(), task.getStatus().name(),
                orZero(task.getSelectedBytes()), orZero(task.getVerifiedBytes()),
                progress(task.getSelectedBytes(), task.getVerifiedBytes()),
                orZero(task.getRetryCount()), task.getErrorCode(), task.getErrorMessage(),
                task.getCreatedAt(), task.getUpdatedAt(), List.of(), List.of());
    }

    private static TaskView taskView(MagnetV2DownloadTask task,
                                     List<MagnetV2DownloadTaskFile> files,
                                     List<MagnetV2TaskEvent> events) {
        List<TaskFileView> fileViews = files.stream()
                .map(file -> new TaskFileView(file.getFileIndex(), Boolean.TRUE.equals(file.getSelected()),
                        orZero(file.getDeclaredBytes()), orZero(file.getVerifiedBytes()), file.getState()))
                .toList();
        List<TaskEventView> eventViews = events.stream()
                .map(event -> new TaskEventView(orZero(event.getSequence()), event.getEventType(),
                        event.getPayloadJson(), event.getCreatedAt()))
                .toList();
        return new TaskView(task.getTaskUuid(), task.getStatus().name(),
                orZero(task.getSelectedBytes()), orZero(task.getVerifiedBytes()),
                progress(task.getSelectedBytes(), task.getVerifiedBytes()),
                orZero(task.getRetryCount()), task.getErrorCode(), task.getErrorMessage(),
                task.getCreatedAt(), task.getUpdatedAt(), fileViews, eventViews);
    }

    private static double progress(Long selectedBytes, Long verifiedBytes) {
        long selected = orZero(selectedBytes);
        if (selected <= 0) {
            return 0.0;
        }
        return Math.round(orZero(verifiedBytes) * 1000.0 / selected) / 10.0;
    }

    private static long orZero(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int safeSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static long currentUserId(HttpServletRequest request) {
        User user = (User) request.getAttribute(AuthInterceptor.ATTR_USER);
        return user.getId();
    }
}
