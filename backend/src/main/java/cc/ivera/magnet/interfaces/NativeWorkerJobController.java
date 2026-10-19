package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeWorkerJobManager;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Authenticated, explicitly opt-in P4.1 experimental work queue.
 * POST submits at most FOUR Pieces, never an automatic full download.
 * Job UUIDs are distinct from the persisted task UUIDs.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/magnet/native")
public class NativeWorkerJobController {
    private final NativeTorrentProperties properties;
    private final NativeWorkerJobManager jobs;

    public record SubmitRound(@NotNull @Min(0) Long expectedRevision,
                              @NotNull @Min(1) @Max(4) Integer maxPieces) {}
    public record Accepted(UUID jobId,NativeWorkerJobManager.Snapshot status) {}
    public record ContinuousRequest(@NotNull @Min(0) Long expectedRevision,
                                    @NotNull @Min(1) @Max(256) Integer piecesPerRound,
                                    @NotNull @Min(1) @Max(10000) Integer maxRounds,
                                    @NotNull @Min(10) @Max(86400) Integer maxSeconds) {}

    @PostMapping("/tasks/{taskUuid}/rounds")
    public ApiResponse<Accepted> submit(HttpServletRequest request,
            @PathVariable UUID taskUuid,@Valid @RequestBody SubmitRound body) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->{
            UUID id=jobs.submit(userId,taskUuid,body.expectedRevision(),body.maxPieces());
            return new Accepted(id,jobs.status(userId,id));
        });
    }

    /** Bounded multi-round execution with terminal PARTIAL if no more verified progress. */
    @PostMapping("/tasks/{taskUuid}/continuous")
    public ApiResponse<Accepted> continuous(HttpServletRequest request,
            @PathVariable UUID taskUuid,@Valid @RequestBody ContinuousRequest body) {
        long userId=owner(request);
        requireEnabled();
        if(!properties.isContinuousWorkerEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "连续原生下载尚未启用，请继续使用单轮下载或现有下载功能");
        if(body.piecesPerRound() > properties.getContinuousMaxPieces()
                || body.maxRounds() > properties.getContinuousMaxRounds()
                || body.maxSeconds() > properties.getContinuousMaxSeconds()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请求参数超出当前允许的最大配置限制");
        }
        return respond(()->{
            UUID jobId=jobs.submitContinuous(userId,taskUuid,
                    body.expectedRevision(),body.piecesPerRound(),
                    body.maxRounds(),body.maxSeconds());
            return new Accepted(jobId,jobs.status(userId,jobId));
        });
    }

    /** Restore the latest owner-scoped in-memory job after a browser refresh. */
    @GetMapping("/tasks/{taskUuid}/worker-job")
    public ApiResponse<NativeWorkerJobManager.Snapshot> taskJob(
            HttpServletRequest request,@PathVariable UUID taskUuid) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->jobs.latestOwnedTaskJob(userId,taskUuid).orElse(null));
    }

    /**
     * Explicit stale RUNNING recovery. Forbidden unless an operator has
     * confirmed single-JVM ownership and enabled the separate feature flag.
     * The manager also refuses to fence while ANY local job is active.
     */
    @PostMapping("/tasks/{taskUuid}/recover-orphan")
    public ApiResponse<cc.ivera.magnet.engine.piece.NativeTaskLifecycleService.TaskState>
            recoverOrphan(HttpServletRequest request,@PathVariable UUID taskUuid,
                          @Valid @RequestBody RecoverOrphan body) {
        long userId=owner(request);
        requireEnabled();
        if(!properties.isSingleInstanceOrphanRecoveryEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "孤儿任务恢复未启用，请确认只有一个 Native Worker 实例运行");
        return respond(()->jobs.recoverOwnedOrphan(userId,taskUuid,body.expectedRevision()));
    }

    public record RecoverOrphan(@NotNull @Min(0) Long expectedRevision) {}

    /** Only this authenticated user's jobs; never expose process-wide queue utilization. */
    @GetMapping("/worker-jobs/queue-health")
    public ApiResponse<NativeWorkerJobManager.OwnerQueueHealth> queueHealth(
            HttpServletRequest request) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->jobs.ownerQueueHealth(userId));
    }

    @GetMapping("/worker-jobs/{jobId}/metrics")
    public ApiResponse<NativeWorkerJobManager.Metrics> metrics(
            HttpServletRequest request,@PathVariable UUID jobId) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->jobs.metrics(userId,jobId));
    }

    @GetMapping("/worker-jobs/{jobId}")
    public ApiResponse<NativeWorkerJobManager.Snapshot> status(
            HttpServletRequest request,@PathVariable UUID jobId) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->jobs.status(userId,jobId));
    }

    /** Pauses the persistent task, closes an active Peer Socket and interrupts its worker. */
    @DeleteMapping("/worker-jobs/{jobId}")
    public ApiResponse<NativeWorkerJobManager.Snapshot> cancel(
            HttpServletRequest request,@PathVariable UUID jobId) {
        long userId=owner(request);
        requireEnabled();
        return respond(()->jobs.cancel(userId,jobId));
    }

    private void requireEnabled() {
        if(!properties.isTaskControlEnabled() || !properties.isEnabled() ||
                !properties.isWorkerRoundEnabled() || !properties.isAsyncWorkerEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "实验性原生下载队列未启用，请继续使用现有下载功能");
    }

    private static long owner(HttpServletRequest request) {
        Object attribute=request.getAttribute(AuthInterceptor.ATTR_USER);
        if(!(attribute instanceof User user) || user.getId()==null || user.getId()<=0)
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user.getId();
    }
    private static <T> ApiResponse<T> respond(Supplier<T> operation) {
        try {return ApiResponse.ok(operation.get());}
        catch(NoSuchElementException e) {
            throw new BusinessException(HttpStatus.NOT_FOUND,"原生任务或作业不存在或无权访问");
        }catch(RejectedExecutionException e) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "原生下载队列或用户并发已满，请稍后重试");
        }catch(IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"作业请求参数无效");
        }catch(IllegalStateException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "任务版本已变化或尚未处于 READY，请刷新后重试");
        }
    }
}
