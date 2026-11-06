package cc.ivera.ed2k.application;

import cc.ivera.ed2k.domain.Ed2kDesiredState;
import cc.ivera.ed2k.domain.Ed2kDownloadTask;
import cc.ivera.ed2k.domain.Ed2kDownloadTaskMapper;
import cc.ivera.ed2k.domain.Ed2kEngineActionMapper;
import cc.ivera.ed2k.domain.Ed2kIdentity;
import cc.ivera.ed2k.domain.Ed2kLinkParser;
import cc.ivera.ed2k.domain.Ed2kParsedLink;
import cc.ivera.ed2k.domain.Ed2kResource;
import cc.ivera.ed2k.domain.Ed2kResourceMapper;
import cc.ivera.ed2k.domain.Ed2kResourceState;
import cc.ivera.ed2k.domain.Ed2kTaskState;
import cc.ivera.ed2k.infrastructure.AmuleProperties;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.AccelerationOutcome;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.EngineDownloadSnapshot;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.EngineHealth;
import cc.ivera.ed2k.infrastructure.FilePathPolicy;
import cc.ivera.ed2k.interfaces.Ed2kDtos.EngineAccelerationResponse;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskFileView;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskPageResponse;
import cc.ivera.ed2k.interfaces.Ed2kDtos.TaskView;
import cc.ivera.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Ed2kTaskService {

    private final AmuleProperties properties;
    private final Ed2kEngineClient engineClient;
    private final Ed2kResourceMapper resourceMapper;
    private final Ed2kDownloadTaskMapper taskMapper;
    private final Ed2kEngineActionMapper actionMapper;
    private final Ed2kResourceCoordinator coordinator;
    private final Ed2kReconcileService reconcileService;
    private final TransactionTemplate transactionTemplate;

    public EngineHealth getEngineHealth() {
        return engineClient.checkHealth();
    }

    public EngineAccelerationResponse accelerateEngine() {
        if (!properties.isEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "电驴下载功能未启用");
        }
        AccelerationOutcome outcome = engineClient.accelerateEngine();
        List<Ed2kResource> activeResources = resourceMapper.listActiveOrPendingResources(
                Math.max(10, properties.getReconcileBatchSize())
        );
        int boosted = 0;
        for (Ed2kResource res : activeResources) {
            if (!Boolean.FALSE.equals(res.getEngineManaged())
                    && (Ed2kResourceState.DOWNLOADING.name().equals(res.getEngineState())
                    || Ed2kResourceState.WAITING.name().equals(res.getEngineState()))) {
                engineClient.boostDownload(new Ed2kIdentity(res.getMd4Hash(), res.getFileSize()), true);
                boosted++;
            }
        }
        try {
            reconcileService.reconcileNow(true);
        } catch (Exception ex) {
            log.debug("reconcileNow after accelerateEngine degraded: {}", ex.getMessage());
        }
        return new EngineAccelerationResponse(
                outcome.preferencesTuned(),
                outcome.networkConnectTriggered(),
                boosted,
                outcome.message()
        );
    }

    public TaskView accelerateTask(long userId, String taskUuid) {
        Ed2kDownloadTask existing = requireOwnedTask(userId, taskUuid);
        if (Ed2kTaskState.COMPLETED.name().equals(existing.getTaskState())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已完成的下载任务无需加速");
        }
        if (Ed2kDesiredState.PAUSED.name().equals(existing.getDesiredState())) {
            throw new BusinessException(HttpStatus.CONFLICT, "任务当前已暂停，请先点击继续后再执行寻源加速");
        }
        Ed2kResource resource = resourceMapper.findById(existing.getResourceId());
        if (resource == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "底层下载资源不存在");
        }
        if (Boolean.FALSE.equals(resource.getEngineManaged())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该资源已在 aMule 外部存在，系统拒绝接管非托管下载");
        }

        Ed2kIdentity identity = new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize());
        engineClient.accelerateEngine();
        engineClient.boostDownload(identity, true);
        reconcileService.reconcileResourceNow(resource.getId(), true);

        Ed2kDownloadTask refreshedTask = requireOwnedTask(userId, taskUuid);
        Ed2kResource refreshedRes = resourceMapper.findById(resource.getId());
        return buildTaskView(refreshedTask, refreshedRes, engineClient.checkHealth().connected());
    }

    public TaskView createTask(long userId, String rawLink) {
        if (!properties.isEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "电驴下载功能未启用");
        }
        Ed2kParsedLink parsed = Ed2kLinkParser.parse(
                rawLink, properties.getMaxLinkLength(), properties.getMaxFileBytes()
        );
        Ed2kResource ensured = coordinator.ensureResource(parsed.identity());

        String taskUuid = coordinator.withResourceLock(ensured.getId(), () -> {
            Path incomingRoot = Path.of(properties.getIncomingRoot()).toAbsolutePath().normalize();
            transactionTemplate.executeWithoutResult(status -> {
                Ed2kResource resInTx = resourceMapper.findById(ensured.getId());
                ensureCompletedStillValidOnDiskInTx(resInTx);
            });

            Ed2kResource currentRes = resourceMapper.findById(ensured.getId());
            if (currentRes != null
                    && (Boolean.FALSE.equals(currentRes.getEngineManaged())
                    || Ed2kResourceState.ERROR.name().equals(currentRes.getEngineState()))) {
                Optional<EngineDownloadSnapshot> snapOpt = engineClient.findByIdentity(parsed.identity());
                if (snapOpt.isPresent()
                        && snapOpt.get().fromSharedList()
                        && !Ed2kResourceCoordinator.isSharedSnapshotPresentOnDisk(incomingRoot, snapOpt.get(), parsed.identity().fileSize())) {
                    engineClient.reloadSharedFiles();
                    snapOpt = Optional.empty();
                }
                if (snapOpt.isEmpty() || Ed2kResourceCoordinator.isManagedByUs(currentRes, snapOpt.get())) {
                    String nextEngineRef = (currentRes.getEngineRef() != null && !currentRes.getEngineRef().isBlank())
                            ? currentRes.getEngineRef()
                            : (snapOpt.isPresent() ? parsed.identity().md4Hash() : null);
                    resetResourceToManagedNotAddedInTx(currentRes, nextEngineRef);
                    currentRes = resourceMapper.findById(ensured.getId());
                } else {
                    markResourceUnmanagedInTx(currentRes);
                    throw new BusinessException(HttpStatus.CONFLICT, "该资源已在 aMule 外部存在，系统拒绝接管非托管下载");
                }
            }

            if (currentRes != null
                    && Ed2kResourceState.NOT_ADDED.name().equals(currentRes.getEngineState())
                    && (currentRes.getEngineRef() == null || currentRes.getEngineRef().isBlank())) {
                Optional<EngineDownloadSnapshot> preExisting = engineClient.findByIdentity(parsed.identity());
                if (preExisting.isPresent()) {
                    EngineDownloadSnapshot snap = preExisting.get();
                    if (snap.fromSharedList()
                            && !Ed2kResourceCoordinator.isSharedSnapshotPresentOnDisk(incomingRoot, snap, parsed.identity().fileSize())) {
                        engineClient.reloadSharedFiles();
                    } else if (!Ed2kResourceCoordinator.isManagedByUs(currentRes, snap)) {
                        markResourceUnmanagedInTx(currentRes);
                        throw new BusinessException(HttpStatus.CONFLICT, "该资源已在 aMule 外部存在，系统拒绝接管非托管下载");
                    }
                }
            }

            return transactionTemplate.execute(status -> {
                Ed2kResource resInTx = resourceMapper.findById(ensured.getId());
                ensureCompletedStillValidOnDiskInTx(resInTx);
                resInTx = resourceMapper.findById(ensured.getId());

                Ed2kDownloadTask doubleCheck = taskMapper.findActiveByUserAndResource(userId, ensured.getId());
                if (doubleCheck != null) {
                    boolean resourceNotCompleted = !Ed2kResourceState.COMPLETED.name().equals(resInTx.getEngineState());
                    if (Ed2kTaskState.ERROR.name().equals(doubleCheck.getTaskState())
                            || (Ed2kTaskState.COMPLETED.name().equals(doubleCheck.getTaskState()) && resourceNotCompleted)) {
                        taskMapper.updateDesiredStateCas(
                                doubleCheck.getTaskUuid(),
                                userId,
                                doubleCheck.getRevision(),
                                Ed2kDesiredState.ACTIVE.name(),
                                Ed2kTaskState.QUEUED.name(),
                                null,
                                null
                        );
                        Ed2kDownloadTask reloadedTask = taskMapper.findOwnedByUuid(doubleCheck.getTaskUuid(), userId);
                        if (reloadedTask != null) {
                            taskMapper.updateProjectionByResource(
                                    reloadedTask.getId(),
                                    userId,
                                    reloadedTask.getRevision(),
                                    Ed2kTaskState.QUEUED.name(),
                                    resInTx.getObservedBytes() != null ? resInTx.getObservedBytes() : 0L,
                                    null,
                                    null,
                                    null
                            );
                        }
                        coordinator.evaluateAndScheduleDemandActionInTx(resInTx.getId());
                    }
                    return doubleCheck.getTaskUuid();
                }

                int activeOwned = taskMapper.countActiveOwned(userId);
                if (activeOwned >= properties.getMaxActiveTasksPerUser()) {
                    throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                            "当前进行中的电驴下载任务已达上限（最多 " + properties.getMaxActiveTasksPerUser() + " 个）");
                }

                LocalDateTime now = LocalDateTime.now();
                boolean alreadyCompleted = Ed2kResourceState.COMPLETED.name().equals(resInTx.getEngineState());
                String newTaskUuid = UUID.randomUUID().toString();
                Ed2kDownloadTask task = Ed2kDownloadTask.builder()
                        .taskUuid(newTaskUuid)
                        .userId(userId)
                        .resourceId(resInTx.getId())
                        .requestedName(parsed.requestedName())
                        .desiredState(Ed2kDesiredState.ACTIVE.name())
                        .taskState(alreadyCompleted ? Ed2kTaskState.COMPLETED.name() : Ed2kTaskState.QUEUED.name())
                        .progressBytes(alreadyCompleted ? resInTx.getFileSize() : (resInTx.getObservedBytes() != null ? resInTx.getObservedBytes() : 0L))
                        .revision(1L)
                        .completedAt(alreadyCompleted ? now : null)
                        .createdAt(now)
                        .updatedAt(now)
                        .deleted(0L)
                        .build();
                taskMapper.insertTask(task);

                coordinator.evaluateAndScheduleDemandActionInTx(resInTx.getId());
                return newTaskUuid;
            });
        });

        reconcileService.reconcileResourceNow(ensured.getId(), false);

        Ed2kResource afterRes = resourceMapper.findById(ensured.getId());
        if (afterRes != null && Boolean.FALSE.equals(afterRes.getEngineManaged())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该资源已在 aMule 外部存在，系统拒绝接管非托管下载");
        }

        Ed2kDownloadTask finalTask = taskMapper.findOwnedByUuid(taskUuid, userId);
        if (finalTask == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "下载任务不存在");
        }
        return buildTaskView(finalTask, afterRes, engineClient.checkHealth().connected());
    }

    public TaskView pauseTask(long userId, String taskUuid, Long expectedRevision) {
        Ed2kDownloadTask existing = requireOwnedTask(userId, taskUuid);
        coordinator.withResourceLock(existing.getResourceId(), () -> {
            transactionTemplate.executeWithoutResult(status -> {
                Ed2kDownloadTask current = requireOwnedTask(userId, taskUuid);
                if (expectedRevision != null && !expectedRevision.equals(current.getRevision())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "任务版本已变更，请刷新后重试");
                }
                if (Ed2kTaskState.COMPLETED.name().equals(current.getTaskState())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "已完成的下载任务不能暂停");
                }
                if (!Ed2kDesiredState.PAUSED.name().equals(current.getDesiredState())) {
                    int updated = taskMapper.updateDesiredStateCas(
                            taskUuid, userId, current.getRevision(),
                            Ed2kDesiredState.PAUSED.name(),
                            Ed2kTaskState.PAUSED.name(),
                            null, null
                    );
                    if (updated == 0) {
                        throw new BusinessException(HttpStatus.CONFLICT, "任务状态已变更，请刷新后重试");
                    }
                    coordinator.evaluateAndScheduleDemandActionInTx(current.getResourceId());
                }
            });
        });

        reconcileService.reconcileResourceNow(existing.getResourceId(), false);
        Ed2kDownloadTask updatedTask = requireOwnedTask(userId, taskUuid);
        Ed2kResource resource = resourceMapper.findById(updatedTask.getResourceId());
        return buildTaskView(updatedTask, resource, engineClient.checkHealth().connected());
    }

    public TaskView resumeTask(long userId, String taskUuid, Long expectedRevision) {
        Ed2kDownloadTask existing = requireOwnedTask(userId, taskUuid);
        coordinator.withResourceLock(existing.getResourceId(), () -> {
            transactionTemplate.executeWithoutResult(status -> {
                Ed2kDownloadTask current = requireOwnedTask(userId, taskUuid);
                if (expectedRevision != null && !expectedRevision.equals(current.getRevision())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "任务版本已变更，请刷新后重试");
                }
                if (!Ed2kDesiredState.ACTIVE.name().equals(current.getDesiredState())) {
                    int activeOwned = taskMapper.countActiveOwned(userId);
                    if (activeOwned >= properties.getMaxActiveTasksPerUser()) {
                        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                                "当前进行中的电驴下载任务已达上限（最多 " + properties.getMaxActiveTasksPerUser() + " 个）");
                    }
                    int updated = taskMapper.updateDesiredStateCas(
                            taskUuid, userId, current.getRevision(),
                            Ed2kDesiredState.ACTIVE.name(),
                            Ed2kTaskState.QUEUED.name(),
                            null, null
                    );
                    if (updated == 0) {
                        throw new BusinessException(HttpStatus.CONFLICT, "任务状态已变更，请刷新后重试");
                    }
                    coordinator.evaluateAndScheduleDemandActionInTx(current.getResourceId());
                }
            });
        });

        reconcileService.reconcileResourceNow(existing.getResourceId(), false);
        Ed2kDownloadTask updatedTask = requireOwnedTask(userId, taskUuid);
        Ed2kResource resource = resourceMapper.findById(updatedTask.getResourceId());
        return buildTaskView(updatedTask, resource, engineClient.checkHealth().connected());
    }

    public void deleteTask(long userId, String taskUuid) {
        Ed2kDownloadTask existing = requireOwnedTask(userId, taskUuid);
        coordinator.withResourceLock(existing.getResourceId(), () -> {
            transactionTemplate.executeWithoutResult(status -> {
                Ed2kDownloadTask current = requireOwnedTask(userId, taskUuid);
                int deleted = taskMapper.logicalDeleteOwned(current.getId(), taskUuid, userId);
                if (deleted == 0) {
                    throw new BusinessException(HttpStatus.NOT_FOUND, "下载任务不存在或已删除");
                }
                coordinator.evaluateAndScheduleDemandActionInTx(current.getResourceId());
            });
        });

        reconcileService.reconcileResourceNow(existing.getResourceId(), false);
    }

    public TaskView getTask(long userId, String taskUuid) {
        Ed2kDownloadTask task = requireOwnedTask(userId, taskUuid);
        reconcileService.reconcileResourceNow(task.getResourceId(), false);
        Ed2kDownloadTask refreshed = requireOwnedTask(userId, taskUuid);
        Ed2kResource resource = resourceMapper.findById(refreshed.getResourceId());
        return buildTaskView(refreshed, resource, engineClient.checkHealth().connected());
    }

    public TaskPageResponse listTasks(long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(30, size));

        try {
            reconcileService.reconcileIfNeeded();
        } catch (Exception ex) {
            log.debug("reconcileIfNeeded degraded during listTasks: {}", ex.getMessage());
        }

        EngineHealth health = engineClient.checkHealth();
        boolean engineConnected = health.connected();

        int total = taskMapper.countOwned(userId);
        if (total == 0) {
            return new TaskPageResponse(Collections.emptyList(), safePage, safeSize, 0, engineConnected, !engineConnected);
        }

        int offset = (safePage - 1) * safeSize;
        List<Ed2kDownloadTask> tasks = taskMapper.listOwned(userId, safeSize, offset);
        List<Long> resourceIds = tasks.stream().map(Ed2kDownloadTask::getResourceId).distinct().toList();
        Map<Long, Ed2kResource> resourceMap = new HashMap<>();
        if (!resourceIds.isEmpty()) {
            for (Ed2kResource res : resourceMapper.findByIds(resourceIds)) {
                resourceMap.put(res.getId(), res);
            }
        }

        List<TaskView> items = new ArrayList<>(tasks.size());
        boolean anyStale = !engineConnected;
        for (Ed2kDownloadTask task : tasks) {
            Ed2kResource res = resourceMap.get(task.getResourceId());
            TaskView view = buildTaskView(task, res, engineConnected);
            if (view.stale()) {
                anyStale = true;
            }
            items.add(view);
        }

        return new TaskPageResponse(items, safePage, safeSize, total, engineConnected, anyStale);
    }

    private Ed2kDownloadTask requireOwnedTask(long userId, String taskUuid) {
        if (taskUuid == null || taskUuid.isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "下载任务不存在");
        }
        Ed2kDownloadTask task = taskMapper.findOwnedByUuid(taskUuid, userId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "下载任务不存在");
        }
        return task;
    }

    private void markResourceUnmanagedInTx(Ed2kResource resource) {
        transactionTemplate.executeWithoutResult(status -> {
            Ed2kResource latest = resourceMapper.findById(resource.getId());
            if (latest != null) {
                resourceMapper.updateStateCas(
                        latest.getId(),
                        latest.getRevision(),
                        Ed2kResourceState.ERROR.name(),
                        false,
                        latest.getEngineRef(),
                        null,
                        null,
                        0L,
                        0L,
                        0,
                        0,
                        LocalDateTime.now(),
                        "UNMANAGED_UPSTREAM_CONFLICT",
                        "该资源已在 aMule 外部存在，系统拒绝接管非托管下载"
                );
            }
        });
    }

    private void resetResourceToManagedNotAddedInTx(Ed2kResource resource, String preservedEngineRef) {
        transactionTemplate.executeWithoutResult(status -> {
            Ed2kResource latest = resourceMapper.findById(resource.getId());
            if (latest != null) {
                resourceMapper.updateStateCas(
                        latest.getId(),
                        latest.getRevision(),
                        Ed2kResourceState.NOT_ADDED.name(),
                        true,
                        preservedEngineRef,
                        null,
                        null,
                        0L,
                        0L,
                        0,
                        0,
                        LocalDateTime.now(),
                        null,
                        null
                );
            }
        });
    }

    private void ensureCompletedStillValidOnDiskInTx(Ed2kResource resource) {
        if (resource == null || !Ed2kResourceState.COMPLETED.name().equals(resource.getEngineState())) {
            return;
        }
        try {
            FilePathPolicy.resolveVerifiedCompletedFile(
                    Path.of(properties.getIncomingRoot()),
                    resource.getCompletedPath(),
                    resource.getFileSize()
            );
        } catch (BusinessException ex) {
            String preservedRef = (resource.getEngineRef() != null && !resource.getEngineRef().isBlank())
                    ? resource.getEngineRef()
                    : resource.getMd4Hash();
            resourceMapper.updateStateCas(
                    resource.getId(),
                    resource.getRevision(),
                    Ed2kResourceState.NOT_ADDED.name(),
                    true,
                    preservedRef,
                    null,
                    null,
                    0L,
                    0L,
                    0,
                    0,
                    LocalDateTime.now(),
                    null,
                    null
            );
            engineClient.reloadSharedFiles();
        }
    }

    private TaskView buildTaskView(Ed2kDownloadTask task, Ed2kResource resource, boolean engineConnected) {
        long fileSize = resource != null && resource.getFileSize() != null ? resource.getFileSize() : 0L;
        String md4Hash = resource != null ? resource.getMd4Hash() : "";
        String engineState = resource != null ? resource.getEngineState() : Ed2kResourceState.NOT_ADDED.name();
        long progressBytes = task.getProgressBytes() != null ? task.getProgressBytes() : 0L;
        if (Ed2kTaskState.COMPLETED.name().equals(task.getTaskState()) && fileSize > 0) {
            progressBytes = fileSize;
        }
        double percent = fileSize > 0
                ? Math.min(100.0, Math.round((progressBytes * 10000.0) / fileSize) / 100.0)
                : 0.0;

        boolean isTaskPaused = Ed2kDesiredState.PAUSED.name().equals(task.getDesiredState());
        long speedBps = (!isTaskPaused && resource != null && resource.getSpeedBps() != null)
                ? resource.getSpeedBps()
                : 0L;
        int sourcesTotal = resource != null && resource.getSourcesTotal() != null ? resource.getSourcesTotal() : 0;
        int sourcesTransferring = (!isTaskPaused && resource != null && resource.getSourcesTransferring() != null)
                ? resource.getSourcesTransferring()
                : 0;

        boolean pendingEngine = resource != null && actionMapper.countPendingByResource(resource.getId()) > 0;
        int activeDemand = resource != null ? taskMapper.countActiveDemandByResource(resource.getId()) : 0;
        boolean sharedDownloading = isTaskPaused && (activeDemand > 0
                || Ed2kResourceState.DOWNLOADING.name().equals(engineState)
                || Ed2kResourceState.WAITING.name().equals(engineState));

        boolean snapshotStale = false;
        if (!Ed2kTaskState.COMPLETED.name().equals(task.getTaskState())
                && !Ed2kTaskState.ERROR.name().equals(task.getTaskState())) {
            if (!engineConnected) {
                snapshotStale = true;
            } else if (resource != null && resource.getLastSnapshotAt() != null) {
                long ageMs = Duration.between(resource.getLastSnapshotAt(), LocalDateTime.now()).toMillis();
                snapshotStale = ageMs > properties.getObservationStaleMs() * 3;
            }
        }

        String statusHint = null;
        if (sharedDownloading) {
            statusHint = "个人已暂停，共享下载可能仍在进行";
        } else if (pendingEngine) {
            statusHint = "引擎待同步";
        }

        boolean downloadable = Ed2kTaskState.COMPLETED.name().equals(task.getTaskState())
                && resource != null
                && Ed2kResourceState.COMPLETED.name().equals(resource.getEngineState())
                && resource.getCompletedPath() != null
                && !resource.getCompletedPath().isBlank();

        List<TaskFileView> files = List.of(
                new TaskFileView(0, task.getRequestedName(), fileSize, downloadable)
        );

        return new TaskView(
                task.getTaskUuid(),
                md4Hash,
                fileSize,
                task.getRequestedName(),
                task.getDesiredState(),
                task.getTaskState(),
                engineState,
                progressBytes,
                percent,
                speedBps,
                sourcesTotal,
                sourcesTransferring,
                task.getRevision() != null ? task.getRevision() : 1L,
                pendingEngine,
                snapshotStale,
                sharedDownloading,
                statusHint,
                task.getErrorCode(),
                task.getErrorMessage(),
                downloadable,
                files,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt()
        );
    }
}
