package cc.ivera.ed2k.application;

import cc.ivera.ed2k.domain.Ed2kActionType;
import cc.ivera.ed2k.domain.Ed2kDownloadTaskMapper;
import cc.ivera.ed2k.domain.Ed2kEngineAction;
import cc.ivera.ed2k.domain.Ed2kEngineActionMapper;
import cc.ivera.ed2k.domain.Ed2kIdentity;
import cc.ivera.ed2k.domain.Ed2kResource;
import cc.ivera.ed2k.domain.Ed2kResourceMapper;
import cc.ivera.ed2k.domain.Ed2kResourceState;
import cc.ivera.ed2k.infrastructure.AmuleProperties;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.CommandOutcome;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.CommandOutcomeStatus;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.CompletedFileCandidate;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.EngineDownloadSnapshot;
import cc.ivera.ed2k.infrastructure.FilePathPolicy;
import cc.ivera.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class Ed2kReconcileService {

    private final AmuleProperties properties;
    private final Ed2kEngineClient engineClient;
    private final Ed2kResourceMapper resourceMapper;
    private final Ed2kDownloadTaskMapper taskMapper;
    private final Ed2kEngineActionMapper actionMapper;
    private final Ed2kResourceCoordinator coordinator;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean batchRunning = new AtomicBoolean(false);
    private final AtomicLong lastReconcileEpochMs = new AtomicLong(0L);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            transactionTemplate.executeWithoutResult(status -> actionMapper.resetStuckApplyingOnStartup());
            reconcileNow(true);
        } catch (Exception ex) {
            log.warn("eD2k 启动自愈对账未完成（将在下次请求时继续对账）: {}", ex.getMessage());
        }
    }

    public void reconcileIfNeeded() {
        long nowMs = System.currentTimeMillis();
        long lastMs = lastReconcileEpochMs.get();
        if (nowMs - lastMs < properties.getReconcileCooldownMs()) {
            return;
        }
        if (!batchRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            lastReconcileEpochMs.set(nowMs);
            runReconcileBatch(false);
        } finally {
            batchRunning.set(false);
        }
    }

    public void reconcileNow(boolean ignoreRetryTimer) {
        lastReconcileEpochMs.set(System.currentTimeMillis());
        runReconcileBatch(ignoreRetryTimer);
    }

    public void reconcileResourceNow(Long resourceId, boolean ignoreRetryTimer) {
        if (resourceId == null) {
            return;
        }
        coordinator.withResourceLock(resourceId, () -> {
            LocalDateTime cutoff = ignoreRetryTimer
                    ? LocalDateTime.now().plusYears(1)
                    : LocalDateTime.now();
            List<Ed2kEngineAction> runnable = actionMapper.listRunnableActions(
                    cutoff, Math.max(10, properties.getReconcileBatchSize())
            );
            for (Ed2kEngineAction action : runnable) {
                if (resourceId.equals(action.getResourceId())) {
                    processSingleAction(action);
                }
            }
            Ed2kResource current = resourceMapper.findById(resourceId);
            if (current != null && isResourceObservationStale(current, ignoreRetryTimer)) {
                refreshResourceObservationUnderLock(current);
            }
        });
    }

    private void runReconcileBatch(boolean ignoreRetryTimer) {
        int batchSize = Math.max(1, properties.getReconcileBatchSize());
        LocalDateTime cutoff = ignoreRetryTimer
                ? LocalDateTime.now().plusYears(1)
                : LocalDateTime.now();
        List<Ed2kEngineAction> actions = actionMapper.listRunnableActions(cutoff, batchSize);
        for (Ed2kEngineAction action : actions) {
            coordinator.withResourceLock(action.getResourceId(), () -> processSingleAction(action));
        }

        List<Ed2kResource> activeResources = resourceMapper.listActiveOrPendingResources(batchSize);
        List<Ed2kResource> staleResources = activeResources.stream()
                .filter(res -> isResourceObservationStale(res, ignoreRetryTimer))
                .toList();
        java.util.Map<Ed2kIdentity, EngineDownloadSnapshot> prefetched = staleResources.size() > 1
                ? engineClient.listSnapshotsByIdentities(
                        staleResources.stream()
                                .map(r -> new Ed2kIdentity(r.getMd4Hash(), r.getFileSize()))
                                .toList()
                )
                : java.util.Collections.emptyMap();

        for (Ed2kResource res : activeResources) {
            coordinator.withResourceLock(res.getId(), () -> {
                Ed2kResource latest = resourceMapper.findById(res.getId());
                if (latest != null && isResourceObservationStale(latest, ignoreRetryTimer)) {
                    Ed2kIdentity id = new Ed2kIdentity(latest.getMd4Hash(), latest.getFileSize());
                    EngineDownloadSnapshot cachedSnap = prefetched.get(id);
                    if (cachedSnap != null) {
                        applySnapshotToResourceUnderLock(
                                latest,
                                cachedSnap,
                                latest.getEngineRef() != null ? latest.getEngineRef() : id.md4Hash()
                        );
                    } else {
                        refreshResourceObservationUnderLock(latest);
                    }
                }
            });
        }
    }

    private boolean isResourceObservationStale(Ed2kResource resource, boolean force) {
        if (Boolean.FALSE.equals(resource.getEngineManaged())) {
            return false;
        }
        Ed2kResourceState state = Ed2kResourceCoordinator.parseResourceState(resource.getEngineState());
        if (state == Ed2kResourceState.COMPLETED || state == Ed2kResourceState.ERROR || state == Ed2kResourceState.NOT_ADDED) {
            return false;
        }
        if (force || resource.getLastSnapshotAt() == null) {
            return true;
        }
        long ageMs = Duration.between(resource.getLastSnapshotAt(), LocalDateTime.now()).toMillis();
        return ageMs >= properties.getObservationStaleMs();
    }

    private void processSingleAction(Ed2kEngineAction action) {
        Ed2kResource resource = resourceMapper.findById(action.getResourceId());
        if (resource == null) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.markFailed(action.getId(), "RESOURCE_MISSING", "资源记录不存在"));
            return;
        }
        if (action.getTargetRevision() < resource.getRevision()) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.supersedeOlderActions(resource.getId(), resource.getRevision()));
            return;
        }

        int currentAttempt = action.getAttemptCount() != null ? action.getAttemptCount() : 0;
        Integer claimed = transactionTemplate.execute(status -> actionMapper.claimApplyingCas(action.getId(), currentAttempt));
        if (claimed == null || claimed == 0) {
            return;
        }

        Ed2kIdentity identity = new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize());
        Ed2kActionType actionType;
        try {
            actionType = Ed2kActionType.valueOf(action.getAction());
        } catch (IllegalArgumentException ex) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.markFailed(action.getId(), "INVALID_ACTION", "未知动作类型"));
            return;
        }

        switch (actionType) {
            case ADD -> executeAddAction(action, resource, identity, currentAttempt);
            case PAUSE -> executePauseAction(action, resource, identity, currentAttempt);
            case RESUME -> executeResumeAction(action, resource, identity, currentAttempt);
            case OBSERVE -> {
                refreshResourceObservationUnderLock(resource);
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
            }
        }
    }

    private void executeAddAction(Ed2kEngineAction action, Ed2kResource resource, Ed2kIdentity identity, int previousAttempts) {
        Path incomingRoot = Path.of(properties.getIncomingRoot()).toAbsolutePath().normalize();
        Optional<EngineDownloadSnapshot> existingOpt = engineClient.findByIdentity(identity);
        boolean staleSharedPurged = false;
        if (existingOpt.isPresent()) {
            EngineDownloadSnapshot snap = existingOpt.get();
            if (snap.fromSharedList() && !Ed2kResourceCoordinator.isSharedSnapshotPresentOnDisk(incomingRoot, snap, identity.fileSize())) {
                engineClient.reloadSharedFiles();
                staleSharedPurged = true;
                existingOpt = Optional.empty();
            }
        }
        if (existingOpt.isPresent()) {
            EngineDownloadSnapshot snap = existingOpt.get();
            boolean managedByUs = Ed2kResourceCoordinator.isManagedByUs(resource, snap) || previousAttempts > 0;
            if (!managedByUs) {
                markResourceUnmanagedConflict(action, resource);
                return;
            }
            transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
            applySnapshotToResourceUnderLock(resource, snap, identity.md4Hash());
            return;
        }

        CommandOutcome outcome = engineClient.addDownload(Ed2kResourceCoordinator.toInternalParsedLink(resource));
        if (outcome.status() == CommandOutcomeStatus.PERMANENT_FAILURE
                && (staleSharedPurged || Ed2kResourceCoordinator.isManagedByUs(resource, null))) {
            engineClient.reloadSharedFiles();
            for (int i = 0; i < 3; i++) {
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Optional<EngineDownloadSnapshot> check = engineClient.findByIdentity(identity);
                if (check.isEmpty() || !check.get().fromSharedList()) {
                    break;
                }
            }
            outcome = engineClient.addDownload(Ed2kResourceCoordinator.toInternalParsedLink(resource));
            if (outcome.status() == CommandOutcomeStatus.PERMANENT_FAILURE) {
                outcome = CommandOutcome.retryable(
                        "AMULE_SHARED_RELOADING",
                        "aMule 正在刷新共享文件索引，稍后自动重试",
                        1500L
                );
            }
        }

        if (outcome.status() == CommandOutcomeStatus.APPLIED) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
            engineClient.boostDownload(identity, false);
            Optional<EngineDownloadSnapshot> afterAdd = engineClient.findByIdentity(identity);
            if (afterAdd.isPresent()
                    && (!afterAdd.get().fromSharedList()
                    || Ed2kResourceCoordinator.isSharedSnapshotPresentOnDisk(incomingRoot, afterAdd.get(), identity.fileSize()))) {
                applySnapshotToResourceUnderLock(resource, afterAdd.get(), identity.md4Hash());
            } else {
                updateResourceSimpleStateInTx(resource, Ed2kResourceState.WAITING, true, identity.md4Hash(), null, null);
            }
            return;
        }

        if (outcome.status() == CommandOutcomeStatus.UNKNOWN) {
            // 超时/结果未知：先记录 engineRef 表示本系统已尝试提交，再查一次上游快照确认是否已生效
            updateResourceSimpleStateInTx(resource, Ed2kResourceState.ADDING, true, identity.md4Hash(), null, null);
            Optional<EngineDownloadSnapshot> checkAfterUnknown = engineClient.findByIdentity(identity);
            if (checkAfterUnknown.isPresent()
                    && (!checkAfterUnknown.get().fromSharedList()
                    || Ed2kResourceCoordinator.isSharedSnapshotPresentOnDisk(incomingRoot, checkAfterUnknown.get(), identity.fileSize()))) {
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
                Ed2kResource reloaded = resourceMapper.findById(resource.getId());
                if (reloaded != null) {
                    applySnapshotToResourceUnderLock(reloaded, checkAfterUnknown.get(), identity.md4Hash());
                }
                return;
            }
            scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, false);
            return;
        }

        if (outcome.status() == CommandOutcomeStatus.PERMANENT_FAILURE) {
            failActionAndResource(action, resource, outcome.errorCode(), outcome.errorMessage());
            return;
        }

        scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, true);
    }

    private void executePauseAction(Ed2kEngineAction action, Ed2kResource resource, Ed2kIdentity identity, int previousAttempts) {
        Optional<EngineDownloadSnapshot> snapOpt = engineClient.findByIdentity(identity);
        if (snapOpt.isPresent()) {
            EngineDownloadSnapshot snap = snapOpt.get();
            if (snap.mappedState() == Ed2kResourceState.PAUSED || snap.completedFlag()) {
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
                applySnapshotToResourceUnderLock(resource, snap, identity.md4Hash());
                return;
            }
        }

        CommandOutcome outcome = engineClient.pauseDownload(identity);
        if (outcome.status() == CommandOutcomeStatus.APPLIED) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
            updateResourcePausedInTx(resource);
            return;
        }
        if (outcome.status() == CommandOutcomeStatus.UNKNOWN) {
            Optional<EngineDownloadSnapshot> after = engineClient.findByIdentity(identity);
            if (after.isPresent() && after.get().mappedState() == Ed2kResourceState.PAUSED) {
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
                applySnapshotToResourceUnderLock(resource, after.get(), identity.md4Hash());
                return;
            }
            scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, false);
            return;
        }
        if (outcome.status() == CommandOutcomeStatus.PERMANENT_FAILURE) {
            failActionAndResource(action, resource, outcome.errorCode(), outcome.errorMessage());
            return;
        }
        scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, true);
    }

    private void executeResumeAction(Ed2kEngineAction action, Ed2kResource resource, Ed2kIdentity identity, int previousAttempts) {
        Optional<EngineDownloadSnapshot> snapOpt = engineClient.findByIdentity(identity);
        if (snapOpt.isPresent()) {
            EngineDownloadSnapshot snap = snapOpt.get();
            if (snap.mappedState() == Ed2kResourceState.DOWNLOADING
                    || snap.mappedState() == Ed2kResourceState.WAITING
                    || snap.completedFlag()) {
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
                applySnapshotToResourceUnderLock(resource, snap, identity.md4Hash());
                return;
            }
        }

        CommandOutcome outcome = engineClient.resumeDownload(identity);
        if (outcome.status() == CommandOutcomeStatus.APPLIED) {
            transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
            Optional<EngineDownloadSnapshot> after = engineClient.findByIdentity(identity);
            if (after.isPresent()) {
                applySnapshotToResourceUnderLock(resource, after.get(), identity.md4Hash());
            } else {
                updateResourceSimpleStateInTx(resource, Ed2kResourceState.WAITING, true, identity.md4Hash(), null, null);
            }
            return;
        }
        if (outcome.status() == CommandOutcomeStatus.UNKNOWN) {
            Optional<EngineDownloadSnapshot> after = engineClient.findByIdentity(identity);
            if (after.isPresent() && after.get().mappedState() != Ed2kResourceState.PAUSED) {
                transactionTemplate.executeWithoutResult(status -> actionMapper.markApplied(action.getId()));
                applySnapshotToResourceUnderLock(resource, after.get(), identity.md4Hash());
                return;
            }
            scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, false);
            return;
        }
        if (outcome.status() == CommandOutcomeStatus.PERMANENT_FAILURE) {
            failActionAndResource(action, resource, outcome.errorCode(), outcome.errorMessage());
            return;
        }
        scheduleRetryOrFail(action, resource, outcome, previousAttempts + 1, true);
    }

    public void refreshResourceObservationUnderLock(Ed2kResource resource) {
        if (resource == null || Boolean.FALSE.equals(resource.getEngineManaged())) {
            return;
        }
        Ed2kIdentity identity = new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize());
        Optional<EngineDownloadSnapshot> snapOpt = engineClient.findByIdentity(identity);
        if (snapOpt.isEmpty()) {
            // 若在 downloads 队列未找到，再检查是否已完成并进入 shared
            Optional<CompletedFileCandidate> completedOpt = engineClient.inspectCompleted(identity);
            if (completedOpt.isPresent()) {
                verifyAndFinalizeCompletedUnderLock(resource, completedOpt.get());
            }
            return;
        }
        applySnapshotToResourceUnderLock(resource, snapOpt.get(),
                resource.getEngineRef() != null ? resource.getEngineRef() : identity.md4Hash());
    }

    private void applySnapshotToResourceUnderLock(Ed2kResource resource, EngineDownloadSnapshot snap, String engineRef) {
        Ed2kResource latest = resourceMapper.findById(resource.getId());
        if (latest == null) {
            return;
        }
        if (snap.fileSize() != latest.getFileSize()) {
            updateResourceSimpleStateInTx(
                    latest,
                    Ed2kResourceState.ERROR,
                    latest.getEngineManaged(),
                    engineRef,
                    "SIZE_MISMATCH",
                    "上游文件大小与 eD2k 链接声明不一致（期望 " + latest.getFileSize() + "，实际 " + snap.fileSize() + "）"
            );
            return;
        }

        if (snap.completedFlag()) {
            Ed2kIdentity identity = new Ed2kIdentity(latest.getMd4Hash(), latest.getFileSize());
            Optional<CompletedFileCandidate> candidateOpt = engineClient.inspectCompleted(identity);
            CompletedFileCandidate candidate = candidateOpt.orElseGet(
                    () -> new CompletedFileCandidate(latest.getMd4Hash(), latest.getFileSize(), snap.name(), true)
            );
            verifyAndFinalizeCompletedUnderLock(latest, candidate);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long clampedObserved = Math.max(0L, Math.min(latest.getFileSize(), snap.completedBytes()));
        transactionTemplate.executeWithoutResult(status -> {
            int updated = resourceMapper.updateStateCas(
                    latest.getId(),
                    latest.getRevision(),
                    snap.mappedState().name(),
                    true,
                    engineRef,
                    latest.getCompletedPath(),
                    latest.getCompletedVerifiedAt(),
                    clampedObserved,
                    snap.speedBps(),
                    snap.sourcesTotal(),
                    snap.sourcesTransferring(),
                    now,
                    snap.errorCode(),
                    snap.errorMessage()
            );
            if (updated > 0) {
                Ed2kResource refreshed = resourceMapper.findById(latest.getId());
                coordinator.syncTaskProjectionsForResourceInTx(refreshed);
                // 若上游实际状态与当前活跃需求不一致且无挂起动作，自动补齐对账动作
                if (actionMapper.countPendingByResource(refreshed.getId()) == 0) {
                    int activeDemand = taskMapper.countActiveDemandByResource(refreshed.getId());
                    Ed2kActionType corrective = Ed2kResourceCoordinator.computeRequiredAction(
                            Ed2kResourceCoordinator.parseResourceState(refreshed.getEngineState()),
                            activeDemand
                    );
                    if (corrective != null) {
                        coordinator.evaluateAndScheduleDemandActionInTx(refreshed.getId());
                    }
                }
            }
        });
    }

    private void verifyAndFinalizeCompletedUnderLock(Ed2kResource resource, CompletedFileCandidate candidate) {
        Ed2kResource latest = resourceMapper.findById(resource.getId());
        if (latest == null) {
            return;
        }
        String relativeName = candidate.relativeFileName();
        Path incomingRoot = Path.of(properties.getIncomingRoot()).toAbsolutePath().normalize();

        try {
            FilePathPolicy.validateRelativeFileName(relativeName);
            Path candidateFile = incomingRoot.resolve(relativeName).normalize();
            if (!Files.exists(candidateFile, LinkOption.NOFOLLOW_LINKS)) {
                // aMule 正在从 temp 搬移到 incoming，暂保持 VERIFYING 状态等待下次轮询核验
                LocalDateTime now = LocalDateTime.now();
                transactionTemplate.executeWithoutResult(status -> {
                    int updated = resourceMapper.updateStateCas(
                            latest.getId(),
                            latest.getRevision(),
                            Ed2kResourceState.VERIFYING.name(),
                            true,
                            latest.getMd4Hash(),
                            null,
                            null,
                            latest.getFileSize(),
                            0L,
                            0,
                            0,
                            now,
                            null,
                            null
                    );
                    if (updated > 0) {
                        coordinator.syncTaskProjectionsForResourceInTx(resourceMapper.findById(latest.getId()));
                    }
                });
                return;
            }

            FilePathPolicy.resolveVerifiedCompletedFile(incomingRoot, relativeName, latest.getFileSize());

            LocalDateTime now = LocalDateTime.now();
            transactionTemplate.executeWithoutResult(status -> {
                int updated = resourceMapper.updateStateCas(
                        latest.getId(),
                        latest.getRevision(),
                        Ed2kResourceState.COMPLETED.name(),
                        true,
                        latest.getMd4Hash(),
                        relativeName,
                        now,
                        latest.getFileSize(),
                        0L,
                        0,
                        0,
                        now,
                        null,
                        null
                );
                if (updated > 0) {
                    Ed2kResource refreshed = resourceMapper.findById(latest.getId());
                    actionMapper.supersedeOlderActions(refreshed.getId(), refreshed.getRevision());
                    coordinator.syncTaskProjectionsForResourceInTx(refreshed);
                }
            });
        } catch (BusinessException ex) {
            updateResourceSimpleStateInTx(
                    latest,
                    Ed2kResourceState.ERROR,
                    true,
                    latest.getMd4Hash(),
                    "FILE_VERIFICATION_FAILED",
                    ex.getMessage()
            );
        }
    }

    private void markResourceUnmanagedConflict(Ed2kEngineAction action, Ed2kResource resource) {
        String errCode = "UNMANAGED_UPSTREAM_CONFLICT";
        String errMsg = "该资源已在 aMule 外部存在，系统拒绝接管非托管下载";
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            actionMapper.markFailed(action.getId(), errCode, errMsg);
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
                        now,
                        errCode,
                        errMsg
                );
                coordinator.syncTaskProjectionsForResourceInTx(resourceMapper.findById(latest.getId()));
            }
        });
    }

    private void failActionAndResource(Ed2kEngineAction action, Ed2kResource resource, String errorCode, String errorMessage) {
        String code = errorCode != null ? errorCode : "AMULE_PERMANENT_ERROR";
        String msg = errorMessage != null ? errorMessage : "aMule 拒绝执行操作";
        transactionTemplate.executeWithoutResult(status -> {
            actionMapper.markFailed(action.getId(), code, msg);
            Ed2kResource latest = resourceMapper.findById(resource.getId());
            if (latest != null) {
                resourceMapper.updateStateCas(
                        latest.getId(),
                        latest.getRevision(),
                        Ed2kResourceState.ERROR.name(),
                        latest.getEngineManaged() != null ? latest.getEngineManaged() : true,
                        latest.getEngineRef(),
                        latest.getCompletedPath(),
                        latest.getCompletedVerifiedAt(),
                        latest.getObservedBytes() != null ? latest.getObservedBytes() : 0L,
                        0L,
                        latest.getSourcesTotal() != null ? latest.getSourcesTotal() : 0,
                        0,
                        LocalDateTime.now(),
                        code,
                        msg
                );
                coordinator.syncTaskProjectionsForResourceInTx(resourceMapper.findById(latest.getId()));
            }
        });
    }

    private void scheduleRetryOrFail(Ed2kEngineAction action,
                                     Ed2kResource resource,
                                     CommandOutcome outcome,
                                     int attemptNumber,
                                     boolean isRetryableEngineError) {
        String errCode = outcome.errorCode() != null ? outcome.errorCode() : "AMULE_RETRYABLE_ERROR";
        String errMsg = outcome.errorMessage() != null ? outcome.errorMessage() : "aMule 暂不可用，等待自动重试";
        boolean isOfflineOrUnavailable = errCode.contains("NETWORK")
                || errCode.contains("UNAVAILABLE")
                || errCode.contains("NOT_CONFIGURED")
                || errCode.contains("TIMEOUT")
                || errCode.contains("RATE_LIMITED")
                || "ec_unavailable".equalsIgnoreCase(errCode);

        if (!isOfflineOrUnavailable && attemptNumber >= properties.getHttpRetryMax()) {
            failActionAndResource(action, resource, errCode, errMsg);
            return;
        }

        long baseDelayMs = outcome.retryAfterMs() != null && outcome.retryAfterMs() > 0
                ? outcome.retryAfterMs()
                : properties.getHttpRetryInitialMs() * (1L << Math.min(10, Math.max(0, attemptNumber - 1)));
        long cappedMs = Math.min(properties.getHttpRetryMaxMs(), Math.max(100L, baseDelayMs));
        long jitterMs = ThreadLocalRandom.current().nextLong(0L, Math.max(1L, cappedMs / 5 + 1));
        LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(cappedMs + jitterMs));

        transactionTemplate.executeWithoutResult(status ->
                actionMapper.markRetryWait(action.getId(), nextRetryAt, errCode, errMsg)
        );
    }

    private void updateResourcePausedInTx(Ed2kResource resource) {
        transactionTemplate.executeWithoutResult(status -> {
            Ed2kResource latest = resourceMapper.findById(resource.getId());
            if (latest == null) {
                return;
            }
            int updated = resourceMapper.updateStateCas(
                    latest.getId(),
                    latest.getRevision(),
                    Ed2kResourceState.PAUSED.name(),
                    true,
                    latest.getEngineRef() != null ? latest.getEngineRef() : latest.getMd4Hash(),
                    latest.getCompletedPath(),
                    latest.getCompletedVerifiedAt(),
                    latest.getObservedBytes() != null ? latest.getObservedBytes() : 0L,
                    0L,
                    latest.getSourcesTotal() != null ? latest.getSourcesTotal() : 0,
                    0,
                    LocalDateTime.now(),
                    null,
                    null
            );
            if (updated > 0) {
                coordinator.syncTaskProjectionsForResourceInTx(resourceMapper.findById(latest.getId()));
            }
        });
    }

    private void updateResourceSimpleStateInTx(Ed2kResource resource,
                                               Ed2kResourceState nextState,
                                               Boolean engineManaged,
                                               String engineRef,
                                               String errorCode,
                                               String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            Ed2kResource latest = resourceMapper.findById(resource.getId());
            if (latest == null) {
                return;
            }
            int updated = resourceMapper.updateStateCas(
                    latest.getId(),
                    latest.getRevision(),
                    nextState.name(),
                    engineManaged != null ? engineManaged : true,
                    engineRef,
                    latest.getCompletedPath(),
                    latest.getCompletedVerifiedAt(),
                    latest.getObservedBytes() != null ? latest.getObservedBytes() : 0L,
                    nextState == Ed2kResourceState.DOWNLOADING && latest.getSpeedBps() != null ? latest.getSpeedBps() : 0L,
                    latest.getSourcesTotal() != null ? latest.getSourcesTotal() : 0,
                    nextState == Ed2kResourceState.DOWNLOADING && latest.getSourcesTransferring() != null ? latest.getSourcesTransferring() : 0,
                    LocalDateTime.now(),
                    errorCode,
                    errorMessage
            );
            if (updated > 0) {
                coordinator.syncTaskProjectionsForResourceInTx(resourceMapper.findById(latest.getId()));
            }
        });
    }
}
