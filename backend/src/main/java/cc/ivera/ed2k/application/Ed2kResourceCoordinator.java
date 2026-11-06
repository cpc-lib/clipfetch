package cc.ivera.ed2k.application;

import cc.ivera.ed2k.domain.Ed2kActionState;
import cc.ivera.ed2k.domain.Ed2kActionType;
import cc.ivera.ed2k.domain.Ed2kDesiredState;
import cc.ivera.ed2k.domain.Ed2kDownloadTask;
import cc.ivera.ed2k.domain.Ed2kDownloadTaskMapper;
import cc.ivera.ed2k.domain.Ed2kEngineAction;
import cc.ivera.ed2k.domain.Ed2kEngineActionMapper;
import cc.ivera.ed2k.domain.Ed2kIdentity;
import cc.ivera.ed2k.domain.Ed2kParsedLink;
import cc.ivera.ed2k.domain.Ed2kResource;
import cc.ivera.ed2k.domain.Ed2kResourceMapper;
import cc.ivera.ed2k.domain.Ed2kResourceState;
import cc.ivera.ed2k.domain.Ed2kTaskState;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.EngineDownloadSnapshot;
import cc.ivera.ed2k.infrastructure.FilePathPolicy;
import cc.ivera.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class Ed2kResourceCoordinator {

    private final Ed2kResourceMapper resourceMapper;
    private final Ed2kDownloadTaskMapper taskMapper;
    private final Ed2kEngineActionMapper actionMapper;
    private final TransactionTemplate transactionTemplate;

    private final ConcurrentHashMap<Long, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();

    public <T> T withResourceLock(Long resourceId, Supplier<T> supplier) {
        ReentrantLock lock = resourceLocks.computeIfAbsent(resourceId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    public void withResourceLock(Long resourceId, Runnable runnable) {
        withResourceLock(resourceId, () -> {
            runnable.run();
            return null;
        });
    }

    public Ed2kResource ensureResource(Ed2kIdentity identity) {
        Ed2kResource existing = resourceMapper.findByIdentity(identity.md4Hash(), identity.fileSize());
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        Ed2kResource created = Ed2kResource.builder()
                .md4Hash(identity.md4Hash())
                .fileSize(identity.fileSize())
                .internalName(buildInternalName(identity))
                .engineState(Ed2kResourceState.NOT_ADDED.name())
                .engineManaged(true)
                .observedBytes(0L)
                .speedBps(0L)
                .sourcesTotal(0)
                .sourcesTransferring(0)
                .revision(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            transactionTemplate.executeWithoutResult(status -> resourceMapper.insertResource(created));
            return created;
        } catch (DuplicateKeyException ex) {
            Ed2kResource concurrent = resourceMapper.findByIdentity(identity.md4Hash(), identity.fileSize());
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    public static String buildInternalName(Ed2kIdentity identity) {
        return "ed2k-" + identity.md4Hash() + "-" + identity.fileSize() + ".bin";
    }

    public static String buildInternalCanonicalLink(Ed2kResource resource) {
        String internalName = resource.getInternalName() != null && !resource.getInternalName().isBlank()
                ? resource.getInternalName()
                : buildInternalName(new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize()));
        return "ed2k://|file|" + internalName + "|" + resource.getFileSize() + "|" + resource.getMd4Hash() + "|/";
    }

    public static Ed2kParsedLink toInternalParsedLink(Ed2kResource resource) {
        Ed2kIdentity identity = new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize());
        String internalName = resource.getInternalName() != null && !resource.getInternalName().isBlank()
                ? resource.getInternalName()
                : buildInternalName(identity);
        return new Ed2kParsedLink(identity, internalName, buildInternalCanonicalLink(resource));
    }

    public static boolean isManagedByUs(Ed2kResource resource, EngineDownloadSnapshot snap) {
        if (resource != null && resource.getEngineRef() != null && !resource.getEngineRef().isBlank()) {
            return true;
        }
        if (snap == null || snap.name() == null || snap.name().isBlank()) {
            return false;
        }
        if (resource != null && resource.getInternalName() != null
                && snap.name().equalsIgnoreCase(resource.getInternalName())) {
            return true;
        }
        if (resource != null && resource.getMd4Hash() != null && resource.getFileSize() != null) {
            String expectedInternal = buildInternalName(new Ed2kIdentity(resource.getMd4Hash(), resource.getFileSize()));
            return snap.name().equalsIgnoreCase(expectedInternal);
        }
        return false;
    }

    public static boolean isSharedSnapshotPresentOnDisk(Path incomingRoot, EngineDownloadSnapshot snap, long expectedSize) {
        if (snap == null || !snap.fromSharedList()) {
            return true;
        }
        if (incomingRoot == null || snap.name() == null || snap.name().isBlank()) {
            return false;
        }
        try {
            FilePathPolicy.resolveVerifiedCompletedFile(incomingRoot, snap.name(), expectedSize);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    public Optional<Ed2kEngineAction> evaluateAndScheduleDemandActionInTx(Long resourceId) {
        Ed2kResource resource = resourceMapper.findById(resourceId);
        if (resource == null) {
            return Optional.empty();
        }
        if (Boolean.FALSE.equals(resource.getEngineManaged())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该资源已在 aMule 外部存在，系统拒绝接管非托管下载");
        }

        int activeDemand = taskMapper.countActiveDemandByResource(resourceId);
        Ed2kResourceState state = parseResourceState(resource.getEngineState());

        if (state == Ed2kResourceState.COMPLETED) {
            syncTaskProjectionsForResourceInTx(resource);
            return Optional.empty();
        }

        Ed2kActionType requiredAction = computeRequiredAction(state, activeDemand);
        if (requiredAction == null) {
            syncTaskProjectionsForResourceInTx(resource);
            return Optional.empty();
        }

        Ed2kEngineAction existingSame = actionMapper.findByDedup(
                resourceId, resource.getRevision(), requiredAction.name()
        );
        if (existingSame != null && isActionStillActive(existingSame.getActionState())) {
            syncTaskProjectionsForResourceInTx(resource);
            return Optional.of(existingSame);
        }

        String nextEngineState = state.name();
        String nextErrorCode = resource.getErrorCode();
        String nextErrorMsg = resource.getErrorMessage();
        if (requiredAction == Ed2kActionType.ADD && (state == Ed2kResourceState.NOT_ADDED || state == Ed2kResourceState.ERROR)) {
            nextEngineState = Ed2kResourceState.ADDING.name();
            nextErrorCode = null;
            nextErrorMsg = null;
        } else if (requiredAction == Ed2kActionType.RESUME && state == Ed2kResourceState.ERROR) {
            nextEngineState = Ed2kResourceState.WAITING.name();
            nextErrorCode = null;
            nextErrorMsg = null;
        }

        int bumped = resourceMapper.bumpRevisionCas(
                resourceId, resource.getRevision(), nextEngineState, nextErrorCode, nextErrorMsg
        );
        if (bumped == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "共享资源状态已变更，请重试");
        }

        Ed2kResource updatedResource = resourceMapper.findById(resourceId);
        long targetRevision = updatedResource.getRevision();
        actionMapper.supersedeOlderActions(resourceId, targetRevision);

        LocalDateTime now = LocalDateTime.now();
        Ed2kEngineAction action = Ed2kEngineAction.builder()
                .actionUuid(UUID.randomUUID().toString())
                .resourceId(resourceId)
                .action(requiredAction.name())
                .targetRevision(targetRevision)
                .actionState(Ed2kActionState.PENDING.name())
                .attemptCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        actionMapper.insertAction(action);

        syncTaskProjectionsForResourceInTx(updatedResource);
        return Optional.of(action);
    }

    public static Ed2kActionType computeRequiredAction(Ed2kResourceState state, int activeDemand) {
        if (state == Ed2kResourceState.COMPLETED) {
            return null;
        }
        if (activeDemand > 0) {
            return switch (state) {
                case NOT_ADDED, ADDING -> Ed2kActionType.ADD;
                case PAUSED -> Ed2kActionType.RESUME;
                case ERROR -> Ed2kActionType.ADD;
                default -> null;
            };
        } else {
            return switch (state) {
                case ADDING, WAITING, DOWNLOADING -> Ed2kActionType.PAUSE;
                default -> null;
            };
        }
    }

    public void syncTaskProjectionsForResourceInTx(Ed2kResource resource) {
        List<Ed2kDownloadTask> tasks = taskMapper.listNonDeletedByResource(resource.getId());
        for (Ed2kDownloadTask task : tasks) {
            Ed2kTaskState nextState = deriveTaskState(task, resource);
            long nextProgress = computeTaskProgressBytes(task, resource, nextState);
            String nextErrorCode = nextState == Ed2kTaskState.ERROR ? resource.getErrorCode() : null;
            String nextErrorMsg = nextState == Ed2kTaskState.ERROR ? resource.getErrorMessage() : null;
            LocalDateTime nextCompletedAt = task.getCompletedAt();
            if (nextState == Ed2kTaskState.COMPLETED && nextCompletedAt == null) {
                nextCompletedAt = resource.getCompletedVerifiedAt() != null
                        ? resource.getCompletedVerifiedAt()
                        : LocalDateTime.now();
            }

            boolean changed = !nextState.name().equals(task.getTaskState())
                    || !Long.valueOf(nextProgress).equals(task.getProgressBytes())
                    || !java.util.Objects.equals(nextErrorCode, task.getErrorCode())
                    || !java.util.Objects.equals(nextErrorMsg, task.getErrorMessage())
                    || !java.util.Objects.equals(nextCompletedAt, task.getCompletedAt());
            if (changed) {
                taskMapper.updateProjectionByResource(
                        task.getId(),
                        task.getUserId(),
                        task.getRevision(),
                        nextState.name(),
                        nextProgress,
                        nextErrorCode,
                        nextErrorMsg,
                        nextCompletedAt
                );
            }
        }
    }

    public static Ed2kTaskState deriveTaskState(Ed2kDownloadTask task, Ed2kResource resource) {
        Ed2kResourceState resState = parseResourceState(resource.getEngineState());
        Ed2kDesiredState desired = parseDesiredState(task.getDesiredState());

        if (desired == Ed2kDesiredState.PAUSED) {
            if (task.getCompletedAt() != null && resState == Ed2kResourceState.COMPLETED) {
                return Ed2kTaskState.COMPLETED;
            }
            return Ed2kTaskState.PAUSED;
        }

        return switch (resState) {
            case NOT_ADDED, ADDING, PAUSED -> Ed2kTaskState.QUEUED;
            case WAITING -> Ed2kTaskState.WAITING;
            case DOWNLOADING -> Ed2kTaskState.DOWNLOADING;
            case VERIFYING -> Ed2kTaskState.VERIFYING;
            case COMPLETED -> Ed2kTaskState.COMPLETED;
            case ERROR -> Ed2kTaskState.ERROR;
        };
    }

    private static long computeTaskProgressBytes(Ed2kDownloadTask task, Ed2kResource resource, Ed2kTaskState nextState) {
        long fileSize = resource.getFileSize() != null ? resource.getFileSize() : 0L;
        if (nextState == Ed2kTaskState.COMPLETED) {
            return fileSize;
        }
        long observed = resource.getObservedBytes() != null ? resource.getObservedBytes() : 0L;
        long clamped = Math.max(0L, Math.min(fileSize, observed));
        if (nextState == Ed2kTaskState.PAUSED) {
            long current = task.getProgressBytes() != null ? task.getProgressBytes() : 0L;
            return Math.max(current, clamped);
        }
        return clamped;
    }

    private static boolean isActionStillActive(String actionState) {
        return Ed2kActionState.PENDING.name().equals(actionState)
                || Ed2kActionState.APPLYING.name().equals(actionState)
                || Ed2kActionState.RETRY_WAIT.name().equals(actionState);
    }

    public static Ed2kResourceState parseResourceState(String value) {
        if (value == null) {
            return Ed2kResourceState.NOT_ADDED;
        }
        try {
            return Ed2kResourceState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Ed2kResourceState.WAITING;
        }
    }

    public static Ed2kDesiredState parseDesiredState(String value) {
        if (value == null) {
            return Ed2kDesiredState.ACTIVE;
        }
        try {
            return Ed2kDesiredState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Ed2kDesiredState.ACTIVE;
        }
    }
}
