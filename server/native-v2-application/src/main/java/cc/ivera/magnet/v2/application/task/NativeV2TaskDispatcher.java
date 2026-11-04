package cc.ivera.magnet.v2.application.task;

import cc.ivera.magnet.v2.application.port.NativeV2EngineSessionRepository;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.application.port.NativeV2TaskEventRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskFileRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskRepository;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import cc.ivera.magnet.v2.domain.task.TaskStatus;
import cc.ivera.magnet.v2.domain.torrent.ParsedTorrent;
import cc.ivera.magnet.v2.domain.torrent.TorrentMetaParser;
import cc.ivera.magnet.v2.engine.spi.AddTorrentCommand;
import cc.ivera.magnet.v2.engine.spi.EngineCommand;
import cc.ivera.magnet.v2.engine.spi.EngineEnvelope;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.engine.spi.NativeV2EngineException;
import cc.ivera.magnet.v2.engine.spi.NativeV2Snapshot;
import cc.ivera.magnet.v2.engine.spi.ResumeSnapshot;
import cc.ivera.magnet.v2.engine.spi.UpdatePrioritiesCommand;
import cc.ivera.magnet.v2.engine.spi.V2FailureKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 调度推进：暂停意图优先、恢复校验、两轮完成与快照幂等。 */
public class NativeV2TaskDispatcher {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;
    private static final EnumSet<TaskStatus> DUE_STATUSES = EnumSet.of(
            TaskStatus.PAUSING, TaskStatus.QUEUED, TaskStatus.RETRY_WAIT, TaskStatus.STARTING,
            TaskStatus.DOWNLOADING, TaskStatus.VERIFYING, TaskStatus.REPAIRING);

    private final NativeV2TaskRepository tasks;
    private final NativeV2TaskFileRepository taskFiles;
    private final NativeV2ResourceRepository resources;
    private final NativeV2EngineSessionRepository sessions;
    private final NativeV2Storage storage;
    private final NativeV2Engine engine;
    private final NativeV2TaskEventRepository events;
    private final NativeV2CompletionPolicy completionPolicy;
    private final NativeV2RetryPolicy retryPolicy;
    private final Duration commandTimeout;
    private final Clock clock;
    private final TorrentMetaParser torrentParser = new TorrentMetaParser();

    public NativeV2TaskDispatcher(NativeV2TaskRepository tasks,
                                  NativeV2TaskFileRepository taskFiles,
                                  NativeV2ResourceRepository resources,
                                  NativeV2EngineSessionRepository sessions,
                                  NativeV2Storage storage,
                                  NativeV2Engine engine,
                                  NativeV2TaskEventRepository events,
                                  NativeV2CompletionPolicy completionPolicy,
                                  NativeV2RetryPolicy retryPolicy,
                                  Duration commandTimeout,
                                  Clock clock) {
        this.tasks = tasks;
        this.taskFiles = taskFiles;
        this.resources = resources;
        this.sessions = sessions;
        this.storage = storage;
        this.engine = engine;
        this.events = events;
        this.completionPolicy = completionPolicy;
        this.retryPolicy = retryPolicy;
        this.commandTimeout = commandTimeout;
        this.clock = clock;
    }

    /** @return 本轮处理的任务数 */
    public int tick() {
        List<MagnetV2DownloadTask> due = tasks.findDue(DUE_STATUSES, LocalDateTime.now(clock), BATCH_SIZE);
        int processed = 0;
        for (MagnetV2DownloadTask task : due) {
            try {
                if (task.getStatus() == TaskStatus.PAUSING) {
                    holdPause(task);
                } else {
                    startOrRefresh(task);
                }
                processed++;
            } catch (NativeV2EngineException error) {
                failOrRetry(task, error);
            } catch (V2DomainException error) {
                fail(task, error.code(), error.getMessage());
            }
        }
        return processed;
    }

    public void applySnapshot(long taskId, long expectedRevision, NativeV2Snapshot snapshot) {
        MagnetV2DownloadTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        if (task.getRevision() != expectedRevision) {
            events.append(task.getUserId(), taskId, "STALE_SNAPSHOT", "{\"expected\":" + expectedRevision + "}");
            return;
        }
        if (task.getStatus() == TaskStatus.PAUSING || task.getStatus().isTerminal()) {
            events.append(task.getUserId(), taskId, "PAUSE_SHADOWED", "{\"state\":\"" + snapshot.state() + "\"}");
            return;
        }
        applyTaskSnapshot(task, snapshot);
    }

    private void startOrRefresh(MagnetV2DownloadTask task) {
        MagnetV2Resource resource = requireResource(task);
        if (task.getStatus() == TaskStatus.QUEUED || task.getStatus() == TaskStatus.RETRY_WAIT
                || task.getStatus() == TaskStatus.STARTING) {
            start(task, resource);
        } else {
            refresh(task, resource);
        }
    }

    private void start(MagnetV2DownloadTask task, MagnetV2Resource resource) {
        EngineEnvelope envelope = envelope(resource, task, ":START");
        byte[] metadata = storage.readMetadata(UUID.fromString(resource.getResourceUuid()));
        byte[] resume = storage.readResume(UUID.fromString(resource.getResourceUuid())).orElse(new byte[0]);
        ParsedTorrent parsed = torrentParser.parse(metadata);
        try {
            engine.addTorrent(addCommand(envelope, task, resource, metadata, resume, parsed));
        } catch (NativeV2EngineException error) {
            if (resume.length > 0 && !error.retryable()) {
                sessions.updateState(resource.getId(), "RECOVERING");
                // 必须换一个 command_id：Worker 按 command_id 缓存拒绝结果，原样重发只会拿到同一条拒绝。
                engine.addTorrent(addCommand(envelope(resource, task, ":RESTART"), task, resource,
                        metadata, new byte[0], parsed));
            } else {
                throw error;
            }
        }
        engine.updateFilePriorities(new UpdatePrioritiesCommand(envelope, priorities(task)));
        engine.resume(new EngineCommand(envelope));
        NativeV2Snapshot snapshot = engine.snapshot(new EngineCommand(envelope));
        applySnapshot(task.getId(), task.getRevision(), snapshot);
    }

    private AddTorrentCommand addCommand(EngineEnvelope envelope, MagnetV2DownloadTask task,
                                         MagnetV2Resource resource, byte[] metadata,
                                         byte[] resume, ParsedTorrent parsed) {
        return new AddTorrentCommand(envelope, metadata, resume,
                "resources/" + resource.getResourceUuid(),
                false, resource.getBtih(), resource.getBtmh(), parsed.trackers());
    }

    private List<Integer> priorities(MagnetV2DownloadTask task) {
        List<MagnetV2DownloadTaskFile> files = taskFiles.findByTask(task.getId());
        int size = files.stream().mapToInt(MagnetV2DownloadTaskFile::getFileIndex).max().orElse(-1) + 1;
        List<Integer> priorities = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            priorities.add(0);
        }
        for (MagnetV2DownloadTaskFile file : files) {
            if (Boolean.TRUE.equals(file.getSelected())) {
                priorities.set(file.getFileIndex(), 1);
            }
        }
        return priorities;
    }

    private void refresh(MagnetV2DownloadTask task, MagnetV2Resource resource) {
        EngineEnvelope envelope = envelope(resource, task, ":SNAPSHOT");
        NativeV2Snapshot snapshot;
        try {
            snapshot = engine.snapshot(new EngineCommand(envelope));
        } catch (NativeV2EngineException error) {
            if (error.kind() != V2FailureKind.CONFLICT) {
                throw error;
            }
            // 资源会话已被释放（Worker 进程重启、同资源历史任务被清理）：重新挂载后继续，不要让任务直接失败。
            start(task, resource);
            return;
        }
        if (!snapshot.metadataReady() && "DISCOVERING".equalsIgnoreCase(snapshot.state())) {
            // Worker 重启后丢失会话时快照仍是 accepted；必须按 metadata + resume 重新挂载，交给 libtorrent 重校验。
            start(task, resource);
            return;
        }
        applySnapshot(task.getId(), task.getRevision(), snapshot);
    }

    /** 暂停是安全意图：Worker 离线时保持 PAUSING 等待下一轮，绝不因瞬时错误把任务转为恢复下载。 */
    private void holdPause(MagnetV2DownloadTask task) {
        try {
            handlePausing(task);
        } catch (NativeV2EngineException error) {
            if (!error.retryable()) {
                throw error;
            }
        }
    }

    private void handlePausing(MagnetV2DownloadTask task) {
        MagnetV2Resource resource = requireResource(task);
        EngineEnvelope envelope = envelope(resource, task, ":PAUSE");
        ResumeSnapshot resume;
        try {
            resume = engine.saveResume(new EngineCommand(envelope));
        } catch (NativeV2EngineException error) {
            if (error.kind() != V2FailureKind.CONFLICT) {
                throw error;
            }
            // Worker 重启后没有该资源会话：先重新挂载任务，再立刻保存 resume 并暂停。
            start(task, resource);
            resume = engine.saveResume(new EngineCommand(envelope));
        }
        if (resume != null && resume.resumeData().length > 0) {
            UUID resourceUuid = UUID.fromString(resource.getResourceUuid());
            storage.writeResume(resourceUuid, resume.resumeData());
            sessions.updateResume(resource.getId(), resume.version());
        }
        engine.pause(new EngineCommand(envelope));
        NativeV2Snapshot snapshot = engine.snapshot(new EngineCommand(envelope));
        sessions.updateState(resource.getId(), snapshot.state());
        if (tasks.updateCas(task.getId(), task.getUserId(), task.getRevision(), TaskStatus.PAUSED,
                task.getVerifiedBytes(), task.getRetryCount(), null, null, null) == 1) {
            sessions.clearActiveTask(resource.getId(), task.getId());
            releaseWorkerFence(task, resource);
            events.append(task.getUserId(), task.getId(), "PAUSED", "{\"status\":\"PAUSED\"}");
        }
    }

    private void applyTaskSnapshot(MagnetV2DownloadTask task, NativeV2Snapshot snapshot) {
        List<MagnetV2DownloadTaskFile> files = taskFiles.findByTask(task.getId());
        long verified = 0;
        boolean allVerified = true;
        for (MagnetV2DownloadTaskFile file : files) {
            long fileVerified = verifiedFor(snapshot, file);
            if (Boolean.TRUE.equals(file.getSelected())) {
                verified += fileVerified;
                allVerified &= fileVerified >= file.getDeclaredBytes();
            }
            String state = fileVerified >= file.getDeclaredBytes() ? "COMPLETE" : "PENDING";
            taskFiles.updateVerified(task.getId(), file.getFileIndex(), fileVerified, state);
        }
        MagnetV2Resource resource = requireResource(task);
        sessions.updateState(resource.getId(), snapshot.state());
        boolean recovering = "RECOVERING".equalsIgnoreCase(snapshot.state());
        TaskStatus next = completionPolicy.next(task.getStatus(), snapshot.metadataReady(), recovering, allVerified);
        if (tasks.updateCas(task.getId(), task.getUserId(), task.getRevision(), next,
                verified, task.getRetryCount(), null, null, null) == 1) {
            if (next.isTerminal()) {
                sessions.clearActiveTask(resource.getId(), task.getId());
                releaseWorkerFence(task, resource);
            }
            if (next != task.getStatus()) {
                events.append(task.getUserId(), task.getId(), "STATUS",
                        "{\"status\":\"" + next + "\"}");
            }
        }
    }

    /**
     * 任务离开活跃状态后释放 Worker 侧的资源 fence，避免租约过期触发 watchdog 退出整个进程。
     * removeData=false：只从 libtorrent 卸载 torrent，保留已下载文件与 resume 数据。
     */
    private void releaseWorkerFence(MagnetV2DownloadTask task, MagnetV2Resource resource) {
        try {
            EngineEnvelope envelope = envelope(resource, task, ":RELEASE");
            engine.remove(new EngineCommand(envelope), false);
        } catch (NativeV2EngineException | V2DomainException ignored) {
            // Worker 可能已离线或会话已不存在，fence 会随进程退出自动释放。
        }
    }

    private static long verifiedFor(NativeV2Snapshot snapshot, MagnetV2DownloadTaskFile file) {
        List<Long> values = snapshot.fileVerifiedBytes();
        if (file.getFileIndex() >= values.size()) {
            return 0;
        }
        return Math.min(values.get(file.getFileIndex()), file.getDeclaredBytes());
    }

    private void failOrRetry(MagnetV2DownloadTask task, NativeV2EngineException error) {
        if (!error.retryable()) {
            fail(task, ErrorCodes.DOWNLOAD_FAILED, error.getMessage());
            return;
        }
        Optional<LocalDateTime> nextRetry =
                retryPolicy.nextRetry(task.getRetryCount(), LocalDateTime.now(clock));
        if (nextRetry.isEmpty()) {
            fail(task, ErrorCodes.DOWNLOAD_FAILED, error.getMessage());
            return;
        }
        tasks.updateCas(task.getId(), task.getUserId(), task.getRevision(), TaskStatus.RETRY_WAIT,
                task.getVerifiedBytes(), task.getRetryCount() + 1, nextRetry.get(),
                ErrorCodes.DOWNLOAD_FAILED, truncate(error.getMessage()));
        events.append(task.getUserId(), task.getId(), "RETRY_WAIT",
                "{\"retryCount\":" + (task.getRetryCount() + 1) + "}");
    }

    private void fail(MagnetV2DownloadTask task, String errorCode, String errorMessage) {
        if (tasks.updateCas(task.getId(), task.getUserId(), task.getRevision(), TaskStatus.FAILED,
                task.getVerifiedBytes(), task.getRetryCount(), null, errorCode, truncate(errorMessage)) == 1) {
            MagnetV2Resource resource = requireResource(task);
            sessions.clearActiveTask(resource.getId(), task.getId());
            releaseWorkerFence(task, resource);
        }
        events.append(task.getUserId(), task.getId(), "FAILED", "{\"code\":\"" + errorCode + "\"}");
    }

    private MagnetV2Resource requireResource(MagnetV2DownloadTask task) {
        return resources.findById(task.getResourceId())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "资源不存在"));
    }

    private EngineEnvelope envelope(MagnetV2Resource resource, MagnetV2DownloadTask task, String suffix) {
        Instant deadline = clock.instant().plus(commandTimeout);
        return new EngineEnvelope(UUID.fromString(resource.getResourceUuid()),
                task.getTaskUuid() + ":" + task.getRevision() + suffix, 1, deadline, deadline);
    }

    private static String truncate(String message) {
        String value = message == null ? "" : message;
        return value.length() <= MAX_ERROR_MESSAGE_LENGTH ? value : value.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
