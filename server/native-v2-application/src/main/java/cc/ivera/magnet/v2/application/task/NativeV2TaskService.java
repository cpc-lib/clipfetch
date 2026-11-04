package cc.ivera.magnet.v2.application.task;

import cc.ivera.magnet.v2.application.V2ConflictException;
import cc.ivera.magnet.v2.application.port.NativeV2EngineSessionRepository;
import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Quota;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskEventRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskFileRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskRepository;
import cc.ivera.magnet.v2.application.port.NativeV2UnitOfWork;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import cc.ivera.magnet.v2.domain.parse.ParseStatus;
import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import cc.ivera.magnet.v2.domain.task.TaskStatus;
import cc.ivera.magnet.v2.domain.task.TaskTransitions;
import cc.ivera.magnet.v2.application.ticket.NativeV2TicketStore;
import cc.ivera.magnet.v2.engine.spi.EngineCommand;
import cc.ivera.magnet.v2.engine.spi.EngineEnvelope;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.engine.spi.NativeV2EngineException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 任务创建与暂停/恢复；创建在单事务内占用资源会话的 active_task_id。 */
public class NativeV2TaskService {

    private final NativeV2ParseJobRepository parseJobs;
    private final NativeV2ResourceRepository resources;
    private final NativeV2TaskRepository tasks;
    private final NativeV2TaskFileRepository taskFiles;
    private final NativeV2EngineSessionRepository sessions;
    private final NativeV2TaskEventRepository events;
    private final NativeV2UnitOfWork unitOfWork;
    private final NativeV2Engine engine;
    private final NativeV2Quota quota;
    private final NativeV2TicketStore tickets;
    private final NativeV2TaskLimits limits;
    private final Clock clock;

    public NativeV2TaskService(NativeV2ParseJobRepository parseJobs,
                               NativeV2ResourceRepository resources,
                               NativeV2TaskRepository tasks,
                               NativeV2TaskFileRepository taskFiles,
                               NativeV2EngineSessionRepository sessions,
                               NativeV2TaskEventRepository events,
                               NativeV2UnitOfWork unitOfWork,
                               NativeV2Engine engine,
                               NativeV2Quota quota,
                               NativeV2TicketStore tickets,
                               NativeV2TaskLimits limits,
                               Clock clock) {
        this.parseJobs = parseJobs;
        this.resources = resources;
        this.tasks = tasks;
        this.taskFiles = taskFiles;
        this.sessions = sessions;
        this.events = events;
        this.unitOfWork = unitOfWork;
        this.engine = engine;
        this.quota = quota;
        this.tickets = tickets;
        this.limits = limits;
        this.clock = clock;
    }

    public MagnetV2DownloadTask create(long userId, String parseUuid, List<Integer> selectedFileIndexes) {
        MagnetV2ParseJob parseJob = parseJobs.findOwned(parseUuid, userId)
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "解析任务不存在"));
        if (parseJob.getStatus() != ParseStatus.READY || parseJob.getResourceId() == null) {
            throw new V2ConflictException("解析任务尚未就绪");
        }
        MagnetV2Resource resource = resources.findById(parseJob.getResourceId())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "资源不存在"));
        List<MagnetV2ResourceFile> files = resources.findFiles(resource.getId());
        if (files.isEmpty()) {
            throw new V2ConflictException("资源没有可下载文件");
        }
        Set<Integer> selected = validateSelected(files, selectedFileIndexes);
        if (!engine.health().connected()) {
            throw new V2DomainException(ErrorCodes.WORKER_UNAVAILABLE, "Native v2 引擎暂不可用，请稍后重试");
        }
        long selectedBytes = files.stream()
                .filter(file -> selected.contains(file.getFileIndex()))
                .mapToLong(MagnetV2ResourceFile::getDeclaredBytes)
                .sum();
        LocalDateTime now = LocalDateTime.now(clock);
        MagnetV2DownloadTask task = MagnetV2DownloadTask.builder()
                .taskUuid(java.util.UUID.randomUUID().toString())
                .userId(userId)
                .resourceId(resource.getId())
                .status(TaskStatus.QUEUED)
                .selectedBytes(selectedBytes)
                .verifiedBytes(0L)
                .priority(0)
                .speedLimit(0L)
                .revision(0L)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        MagnetV2DownloadTask[] saved = new MagnetV2DownloadTask[1];
        unitOfWork.run(() -> {
            MagnetV2DownloadTask inserted = tasks.insert(task);
            sessions.ensureExists(resource.getId(), resource.getResourceUuid());
            if (sessions.claimActiveTask(resource.getId(), inserted.getId(), userId) != 1) {
                throw new V2ConflictException("该资源已有其他用户的活跃任务");
            }
            taskFiles.insertAll(taskFileRows(inserted, files, selected, now));
            events.append(userId, inserted.getId(), "CREATED", "{\"status\":\"QUEUED\"}");
            saved[0] = inserted;
        });
        return saved[0];
    }

    public List<MagnetV2DownloadTask> listOwned(long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return tasks.listOwned(userId, safePage * safeSize, safeSize);
    }

    public TaskDetail detail(long userId, String taskUuid) {
        MagnetV2DownloadTask task = requireOwned(userId, taskUuid);
        return new TaskDetail(task, taskFiles.findByTask(task.getId()),
                events.listRecent(task.getId(), 10));
    }

    public String issueTicket(long userId, String taskUuid, int fileIndex) {
        MagnetV2DownloadTask task = requireOwned(userId, taskUuid);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new V2ConflictException("任务未完成，不能下载");
        }
        MagnetV2DownloadTaskFile taskFile = taskFiles.findByTask(task.getId()).stream()
                .filter(file -> file.getFileIndex() == fileIndex)
                .findFirst()
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "文件不存在"));
        if (!Boolean.TRUE.equals(taskFile.getSelected()) || !"COMPLETE".equals(taskFile.getState())) {
            throw new V2ConflictException("文件未完成，不能下载");
        }
        MagnetV2Resource resource = resources.findById(task.getResourceId())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "资源不存在"));
        String relativePath = resources.findFiles(resource.getId()).stream()
                .filter(file -> file.getFileIndex() == fileIndex)
                .map(MagnetV2ResourceFile::getRelativePath)
                .findFirst()
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "文件不存在"));
        quota.consumeTicketRate(userId);
        return tickets.issue(userId, task.getTaskUuid(), resource.getResourceUuid(), fileIndex,
                relativePath, clock.instant());
    }

    public MagnetV2DownloadTask pause(long userId, String taskUuid) {
        MagnetV2DownloadTask task = requireOwned(userId, taskUuid);
        if (!TaskTransitions.canPause(task.getStatus())) {
            throw new V2ConflictException("当前任务状态不允许暂停");
        }
        if (tasks.updateCas(task.getId(), userId, task.getRevision(), TaskStatus.PAUSING,
                task.getVerifiedBytes(), task.getRetryCount(), null, null, null) != 1) {
            throw new V2ConflictException("任务状态已变化，请刷新后重试");
        }
        return reload(task.getId());
    }

    public MagnetV2DownloadTask resume(long userId, String taskUuid) {
        MagnetV2DownloadTask task = requireOwned(userId, taskUuid);
        if (task.getStatus() != TaskStatus.PAUSED) {
            throw new V2ConflictException("只有已暂停任务可以恢复");
        }
        if (!engine.health().connected()) {
            throw new V2DomainException(ErrorCodes.WORKER_UNAVAILABLE, "Native v2 引擎暂不可用，请稍后重试");
        }
        if (tasks.updateCas(task.getId(), userId, task.getRevision(), TaskStatus.QUEUED,
                task.getVerifiedBytes(), task.getRetryCount(), null, null, null) != 1) {
            throw new V2ConflictException("任务状态已变化，请刷新后重试");
        }
        return reload(task.getId());
    }

    public void delete(long userId, String taskUuid) {
        MagnetV2DownloadTask task = requireOwned(userId, taskUuid);
        long resourceId = task.getResourceId();
        // 同一资源只允许一个活跃任务；删除历史任务时不得卸载别的任务正在使用的 Worker 会话，
        // 否则对方下一次快照会因 fence 已释放而直接失败。
        boolean ownsSession = sessions.find(resourceId)
                .map(session -> session.getActiveTaskId() == null
                        || session.getActiveTaskId().equals(task.getId()))
                .orElse(true);
        unitOfWork.run(() -> {
            sessions.clearActiveTask(resourceId, task.getId());
            taskFiles.deleteByTaskAndUser(task.getId(), userId);
            events.deleteByTask(task.getId());
            tasks.deleteByIdAndUser(task.getId(), userId);
        });
        if (!ownsSession) {
            return;
        }
        // 事务提交后释放 Worker 侧 fence，避免租约过期触发 watchdog 退出进程。
        try {
            resources.findById(resourceId).ifPresent(resource -> {
                Instant deadline = clock.instant().plusSeconds(30);
                EngineEnvelope envelope = new EngineEnvelope(
                        java.util.UUID.fromString(resource.getResourceUuid()),
                        task.getTaskUuid() + ":DELETE", 1, deadline, deadline);
                engine.remove(new EngineCommand(envelope), false);
            });
        } catch (NativeV2EngineException | V2DomainException ignored) {
            // Worker 可能已离线，fence 随进程退出自动释放。
        }
    }

    private MagnetV2DownloadTask requireOwned(long userId, String taskUuid) {
        return tasks.findOwned(taskUuid, userId)
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "任务不存在"));
    }

    private MagnetV2DownloadTask reload(long id) {
        return tasks.findById(id)
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "任务不存在"));
    }

    private Set<Integer> validateSelected(List<MagnetV2ResourceFile> files, List<Integer> selectedFileIndexes) {
        if (selectedFileIndexes == null || selectedFileIndexes.isEmpty()) {
            throw new V2DomainException(ErrorCodes.VALIDATION, "必须至少选择一个文件");
        }
        if (selectedFileIndexes.size() > limits.maxSelectedFiles()) {
            throw new V2DomainException(ErrorCodes.VALIDATION, "选择文件数超过上限");
        }
        Set<Integer> available = files.stream()
                .map(MagnetV2ResourceFile::getFileIndex)
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> selected = new LinkedHashSet<>();
        for (Integer index : selectedFileIndexes) {
            if (index == null || !available.contains(index)) {
                throw new V2DomainException(ErrorCodes.VALIDATION, "选择的文件不存在");
            }
            if (!selected.add(index)) {
                throw new V2DomainException(ErrorCodes.VALIDATION, "文件索引重复");
            }
        }
        return selected;
    }

    private List<MagnetV2DownloadTaskFile> taskFileRows(MagnetV2DownloadTask task,
                                                        List<MagnetV2ResourceFile> files,
                                                        Set<Integer> selected,
                                                        LocalDateTime now) {
        List<MagnetV2DownloadTaskFile> rows = new ArrayList<>(files.size());
        for (MagnetV2ResourceFile file : files) {
            rows.add(MagnetV2DownloadTaskFile.builder()
                    .userId(task.getUserId())
                    .taskId(task.getId())
                    .fileIndex(file.getFileIndex())
                    .selected(selected.contains(file.getFileIndex()))
                    .declaredBytes(file.getDeclaredBytes())
                    .verifiedBytes(0L)
                    .state("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return rows;
    }
}
