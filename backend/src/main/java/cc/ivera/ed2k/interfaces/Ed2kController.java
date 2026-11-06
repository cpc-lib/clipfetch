package cc.ivera.ed2k.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.ed2k.application.Ed2kTaskService;
import cc.ivera.ed2k.interfaces.Ed2kDtos.CreateTaskRequest;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskActionRequest;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskPageResponse;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskView;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ed2k")
@RequiredArgsConstructor
public class Ed2kController {

    private final Ed2kTaskService taskService;

    @PostMapping("/tasks")
    public ApiResponse<TaskView> createTask(@Valid @RequestBody CreateTaskRequest body,
                                            HttpServletRequest request) {
        long userId = currentUserId(request);
        return ApiResponse.ok(taskService.createTask(userId, body.link()));
    }

    @GetMapping("/tasks")
    public ApiResponse<TaskPageResponse> listTasks(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   HttpServletRequest request) {
        long userId = currentUserId(request);
        return ApiResponse.ok(taskService.listTasks(userId, page, size));
    }

    @GetMapping("/tasks/{taskUuid}")
    public ApiResponse<TaskView> getTask(@PathVariable String taskUuid,
                                         HttpServletRequest request) {
        long userId = currentUserId(request);
        return ApiResponse.ok(taskService.getTask(userId, taskUuid));
    }

    @PostMapping("/tasks/{taskUuid}/pause")
    public ApiResponse<TaskView> pauseTask(@PathVariable String taskUuid,
                                           @RequestBody(required = false) TaskActionRequest body,
                                           HttpServletRequest request) {
        long userId = currentUserId(request);
        Long expectedRevision = body != null ? body.expectedRevision() : null;
        return ApiResponse.ok(taskService.pauseTask(userId, taskUuid, expectedRevision));
    }

    @PostMapping("/tasks/{taskUuid}/resume")
    public ApiResponse<TaskView> resumeTask(@PathVariable String taskUuid,
                                            @RequestBody(required = false) TaskActionRequest body,
                                            HttpServletRequest request) {
        long userId = currentUserId(request);
        Long expectedRevision = body != null ? body.expectedRevision() : null;
        return ApiResponse.ok(taskService.resumeTask(userId, taskUuid, expectedRevision));
    }

    @PostMapping("/tasks/{taskUuid}/accelerate")
    public ApiResponse<TaskView> accelerateTask(@PathVariable String taskUuid,
                                                HttpServletRequest request) {
        long userId = currentUserId(request);
        return ApiResponse.ok(taskService.accelerateTask(userId, taskUuid));
    }

    @DeleteMapping("/tasks/{taskUuid}")
    public ApiResponse<Map<String, Object>> deleteTask(@PathVariable String taskUuid,
                                                       HttpServletRequest request) {
        long userId = currentUserId(request);
        taskService.deleteTask(userId, taskUuid);
        return ApiResponse.ok(Map.of("deleted", true, "taskUuid", taskUuid));
    }

    private long currentUserId(HttpServletRequest request) {
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
