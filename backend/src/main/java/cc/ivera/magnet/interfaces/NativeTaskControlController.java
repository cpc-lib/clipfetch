package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeFileExportService;
import cc.ivera.magnet.engine.piece.NativeTaskLifecycleService;
import cc.ivera.magnet.engine.piece.NativeTaskCheckpointFactory;
import java.io.IOException;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Experimental native task CONTROL PLANE, not a downloader.
 *
 * prepare -> READY is NOT "start downloading". Only a separately implemented,
 * authorized worker can claim RUNNING and download verified Pieces.
 * User identity always comes from the authenticated JWT interceptor.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/magnet/native/tasks")
public class NativeTaskControlController {
    private final NativeTorrentProperties properties;
    private final NativeTaskLifecycleService tasks;
    private final NativeTaskCheckpointFactory checkpoints;
    private final NativeFileExportService files;

    public record RevisionRequest(@NotNull @Min(0) Long expectedRevision) {}

    @GetMapping
    public ApiResponse<List<NativeTaskLifecycleService.TaskState>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        long owner=owner(request);
        requireEnabled();
        return respond(() -> tasks.list(owner,page,pageSize));
    }

    @GetMapping("/{taskUuid}")
    public ApiResponse<NativeTaskLifecycleService.TaskState> get(
            HttpServletRequest request,@PathVariable UUID taskUuid) {
        long owner=owner(request);
        requireEnabled();
        return respond(() -> tasks.get(owner,taskUuid));
    }

    /** Prepare a task; does not launch network traffic. */
    @PostMapping("/{taskUuid}/prepare")
    public ApiResponse<NativeTaskLifecycleService.TaskState> prepare(
            HttpServletRequest request,@PathVariable UUID taskUuid,
            @Valid @RequestBody RevisionRequest body) {
        long owner=owner(request);
        requireEnabled();
        return respond(() -> tasks.prepare(owner,taskUuid,body.expectedRevision()));
    }

    /** CAS-fences the old Worker before any next Piece request. */
    @PostMapping("/{taskUuid}/pause")
    public ApiResponse<NativeTaskLifecycleService.TaskState> pause(
            HttpServletRequest request,@PathVariable UUID taskUuid,
            @Valid @RequestBody RevisionRequest body) {
        long owner=owner(request);
        requireEnabled();
        return respond(() -> tasks.pause(owner,taskUuid,body.expectedRevision()));
    }

    /** Rehash a stopped task's durable Pieces before trusting its progress after a crash. */
    @PostMapping("/{taskUuid}/reconcile")
    public ApiResponse<NativeTaskLifecycleService.TaskState> reconcile(
            HttpServletRequest request,@PathVariable UUID taskUuid,
            @Valid @RequestBody RevisionRequest body) {
        long owner=owner(request);
        requireEnabled();
        try {
            var before=respond(()->tasks.get(owner,taskUuid)).data();
            if(before.revision()!=body.expectedRevision() || "RUNNING".equals(before.state()))
                throw new BusinessException(HttpStatus.CONFLICT,
                        "任务版本已变化或仍在运行，请先停止 Worker 再校验");
            var checkpoint=checkpoints.openOwned(owner,taskUuid);
            return respond(()->{
                try {return tasks.reconcileStopped(owner,taskUuid,body.expectedRevision(),checkpoint);}
                catch(IOException e) {throw new IllegalStateException("Checkpoint verification failed",e);}
            });
        }catch(IOException | SecurityException failure) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "磁盘校验失败或任务文件不匹配，请检查存储后重试");
        }
    }

    /** Only logical deletion; no files are deleted by this endpoint. */
    @DeleteMapping("/{taskUuid}")
    public ApiResponse<Boolean> delete(
            HttpServletRequest request,@PathVariable UUID taskUuid,
            @RequestParam @Min(0) long expectedRevision) {
        long owner=owner(request);
        requireEnabled();
        if(expectedRevision<0)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"任务版本号不能为负数");
        return respond(() -> {
            tasks.logicalDelete(owner,taskUuid,expectedRevision);
            return true;
        });
    }

    /** Selected files of a COMPLETED task; used before exporting to a local folder. */
    @GetMapping("/{taskUuid}/files")
    public ApiResponse<List<NativeFileExportService.FileView>> listFiles(
            HttpServletRequest request,@PathVariable UUID taskUuid) {
        long owner=owner(request);
        requireEnabled();
        return respond(() -> files.list(owner,taskUuid));
    }

    /**
     * Authenticated byte stream for one completed file. Deliberately NOT a
     * whitelisted or ticket endpoint: the browser fetch sends the JWT itself.
     */
    @GetMapping("/{taskUuid}/files/{fileIndex}/content")
    public void fileContent(HttpServletRequest request,@PathVariable UUID taskUuid,
                            @PathVariable int fileIndex,HttpServletResponse response) {
        long owner=owner(request);
        requireEnabled();
        files.stream(files.resolveDownload(owner,taskUuid,fileIndex),response);
    }

    private void requireEnabled() {
        if(!properties.isTaskControlEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "原生任务管理功能尚未开启；现有 qBittorrent 下载功能不受影响");
    }
    private static long owner(HttpServletRequest request) {
        Object attribute=request.getAttribute(AuthInterceptor.ATTR_USER);
        if(!(attribute instanceof User user) || user.getId()==null || user.getId()<=0)
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user.getId();
    }
    private static <T> ApiResponse<T> respond(Supplier<T> operation) {
        try {
            return ApiResponse.ok(operation.get());
        }catch(NoSuchElementException e) {
            throw new BusinessException(HttpStatus.NOT_FOUND,"原生任务不存在或无权访问");
        }catch(IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"请求参数无效，请检查分页或版本号");
        }catch(IllegalStateException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "任务状态或版本已发生变化，请刷新后重试");
        }
    }
}
