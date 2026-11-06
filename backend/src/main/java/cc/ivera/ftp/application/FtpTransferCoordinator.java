package cc.ivera.ftp.application;

import cc.ivera.ftp.domain.FtpAuthMode;
import cc.ivera.ftp.domain.FtpDownloadTask;
import cc.ivera.ftp.domain.FtpDownloadTaskMapper;
import cc.ivera.ftp.domain.FtpProtocol;
import cc.ivera.ftp.domain.FtpTaskCredential;
import cc.ivera.ftp.domain.FtpTaskCredentialMapper;
import cc.ivera.ftp.domain.FtpTaskState;
import cc.ivera.ftp.domain.FtpTransferCheckpoint;
import cc.ivera.ftp.domain.FtpTransferCheckpointMapper;
import cc.ivera.ftp.infrastructure.CommonsNetFtpClient;
import cc.ivera.ftp.infrastructure.FtpCredentialCipher;
import cc.ivera.ftp.infrastructure.FtpPathPolicy;
import cc.ivera.ftp.infrastructure.FtpProperties;
import cc.ivera.shared.web.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class FtpTransferCoordinator {

    private final FtpProperties properties;
    private final CommonsNetFtpClient ftpClient;
    private final FtpCredentialCipher credentialCipher;
    private final FtpPathPolicy pathPolicy;
    private final FtpDownloadTaskMapper taskMapper;
    private final FtpTaskCredentialMapper credentialMapper;
    private final FtpTransferCheckpointMapper checkpointMapper;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor retries;
    private final Map<Long, Semaphore> userPermits = new ConcurrentHashMap<>();
    private final Map<String, ActiveTransfer> active = new ConcurrentHashMap<>();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    public FtpTransferCoordinator(FtpProperties properties,
                                  CommonsNetFtpClient ftpClient,
                                  FtpCredentialCipher credentialCipher,
                                  FtpPathPolicy pathPolicy,
                                  FtpDownloadTaskMapper taskMapper,
                                  FtpTaskCredentialMapper credentialMapper,
                                  FtpTransferCheckpointMapper checkpointMapper) {
        this.properties = properties;
        this.ftpClient = ftpClient;
        this.credentialCipher = credentialCipher;
        this.pathPolicy = pathPolicy;
        this.taskMapper = taskMapper;
        this.credentialMapper = credentialMapper;
        this.checkpointMapper = checkpointMapper;
        int concurrency = Math.max(1, properties.getMaxConcurrent());
        this.workers = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "ftp-transfer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.retries = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ftp-retry");
            thread.setDaemon(true);
            return thread;
        });
        this.retries.setRemoveOnCancelPolicy(true);
    }

    public void submit(long userId, String taskUuid) {
        if (!properties.isConfigured() || !scheduled.add(taskUuid)) {
            return;
        }
        try {
            workers.execute(() -> {
                scheduled.remove(taskUuid);
                runClaimed(userId, taskUuid);
            });
        } catch (RejectedExecutionException ex) {
            scheduled.remove(taskUuid);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FTP 下载队列已满，请稍后重试");
        }
    }

    public void cancel(String taskUuid) {
        ActiveTransfer transfer = active.get(taskUuid);
        if (transfer != null) {
            transfer.cancelled = true;
            CommonsNetFtpClient.Session session = transfer.session;
            if (session != null) {
                session.abort();
            }
        }
    }

    public boolean cancelAndAwait(String taskUuid, long timeoutMs) {
        ActiveTransfer transfer = active.get(taskUuid);
        if (transfer == null) {
            return true;
        }
        cancel(taskUuid);
        try {
            return transfer.finished.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void runClaimed(long userId, String taskUuid) {
        Semaphore permit = userPermits.computeIfAbsent(userId,
                ignored -> new Semaphore(Math.max(1, properties.getMaxConcurrentPerUser())));
        if (!permit.tryAcquire()) {
            scheduleSubmit(userId, taskUuid, 500L);
            return;
        }
        try {
            FtpDownloadTask queued = taskMapper.findOwnedByUuid(taskUuid, userId);
            if (queued == null || !(FtpTaskState.QUEUED.name().equals(queued.getStatus())
                    || FtpTaskState.RETRY_WAIT.name().equals(queued.getStatus()))) {
                return;
            }
            if (taskMapper.claimQueued(queued.getId(), userId, queued.getRevision()) == 0) {
                return;
            }
            FtpDownloadTask claimed = taskMapper.findOwnedByUuid(taskUuid, userId);
            if (claimed == null) {
                return;
            }
            ActiveTransfer transfer = new ActiveTransfer();
            active.put(taskUuid, transfer);
            try {
                FtpDownloadTask current = taskMapper.findOwnedByUuid(taskUuid, userId);
                if (current == null || !FtpTaskState.CONNECTING.name().equals(current.getStatus())
                        || !claimed.getExecutionEpoch().equals(current.getExecutionEpoch())) {
                    return;
                }
                execute(current, transfer);
            } finally {
                active.remove(taskUuid, transfer);
                transfer.finished.countDown();
            }
        } finally {
            permit.release();
        }
    }

    private void execute(FtpDownloadTask task, ActiveTransfer transfer) {
        FtpTaskCredential credential = credentialMapper.findOwnedByTaskId(task.getId(), task.getUserId());
        FtpTransferCheckpoint checkpoint = checkpointMapper.findOwnedByTaskId(task.getId(), task.getUserId());
        if (credential == null || checkpoint == null) {
            updateState(task, FtpTaskState.FAILED, 0L, null, task.getRetryCount(), null,
                    "PERSISTED_STATE_MISSING", "FTP 任务持久状态不完整", null);
            return;
        }

        String username;
        String password;
        try {
            username = decrypt(task, credential, "username", credential.getEncryptedUsername(), credential.getUsernameNonce());
            password = decrypt(task, credential, "password", credential.getEncryptedPassword(), credential.getPasswordNonce());
        } catch (IllegalStateException ex) {
            updateState(task, FtpTaskState.BLOCKED_CREDENTIALS, safeDownloaded(task), task.getTotalBytes(),
                    task.getRetryCount(), null, "CREDENTIAL_KEY_UNAVAILABLE", "FTP 凭据无法解密，请检查服务端密钥配置", null);
            return;
        }

        CommonsNetFtpClient.ConnectionRequest request = new CommonsNetFtpClient.ConnectionRequest(
                FtpProtocol.valueOf(task.getProtocol()), task.getHost(), task.getPort(),
                FtpAuthMode.valueOf(credential.getAuthMode()), username, password);
        try (CommonsNetFtpClient.Session session = ftpClient.connect(request)) {
            transfer.session = session;
            if (transfer.cancelled) {
                throw new PauseSignal();
            }
            CommonsNetFtpClient.RemoteIdentity remote = session.inspect(task.getRemotePath());
            if (remote.size() != null && remote.size() > properties.getMaxFileBytes()) {
                updateState(task, FtpTaskState.FAILED, safeDownloaded(task), remote.size(), task.getRetryCount(), null,
                        "FILE_TOO_LARGE", "远端文件超过管理员配置的大小上限", null);
                return;
            }

            Path part;
            Object partFileKey;
            long downloaded;
            String sha256;
            try (FtpPathPolicy.OpenPartFile opened = pathPolicy.openPartFile(task.getUserId(), task.getTaskUuid())) {
                part = opened.path();
                partFileKey = opened.fileKey();
                FileChannel output = opened.channel();
                long actualBytes = output.size();
                long committedBytes = Math.min(checkpoint.getCommittedBytes(), actualBytes);
                if (actualBytes != committedBytes) {
                    output.truncate(committedBytes);
                    output.force(true);
                }
                if (committedBytes > 0 && !sameRemoteIdentity(checkpoint, remote)) {
                    updateState(task, FtpTaskState.NEEDS_RESTART, committedBytes, remote.size(), task.getRetryCount(), null,
                            "REMOTE_IDENTITY_CHANGED", "远端文件身份已变化或不足以安全续传，请确认后从头下载", null);
                    return;
                }

                long checkpointRevision = persistCheckpoint(checkpoint, committedBytes, remote, null);
                if (!updateState(task, FtpTaskState.DOWNLOADING, committedBytes, remote.size(), task.getRetryCount(), null,
                        null, null, null)) {
                    throw new PauseSignal();
                }
                downloaded = transferToPart(task, transfer, session, checkpoint, checkpointRevision,
                        output, committedBytes, remote);
                sha256 = sha256(output, downloaded);
            }

            if (!updateState(task, FtpTaskState.VERIFYING, downloaded, remote.size(), task.getRetryCount(), null,
                    null, null, null)) {
                throw new PauseSignal();
            }
            if (remote.size() != null && downloaded != remote.size()) {
                throw new IOException("FTP 文件长度与远端声明不一致");
            }
            FtpTransferCheckpoint latest = checkpointMapper.findOwnedByTaskId(task.getId(), task.getUserId());
            if (requiresIndependentVerification(remote, task.getExpectedSha256())) {
                if (latest != null) {
                    persistCheckpoint(latest, downloaded, remote, sha256);
                }
                updateState(task, FtpTaskState.NEEDS_VERIFICATION, downloaded, null, task.getRetryCount(), null,
                        "REMOTE_SIZE_UNKNOWN", "远端服务器未提供文件长度，且未配置可信 SHA-256，无法确认文件完整性", null);
                return;
            }
            if (task.getExpectedSha256() != null && !task.getExpectedSha256().equalsIgnoreCase(sha256)) {
                updateState(task, FtpTaskState.FAILED, downloaded, remote.size(), task.getRetryCount(), null,
                        "SHA256_MISMATCH", "文件 SHA-256 与期望值不一致", null);
                return;
            }
            Path verifiedPart = pathPolicy.verifiedPartFile(task.getUserId(), task.getTaskUuid(), partFileKey);
            moveCompleted(verifiedPart, pathPolicy.completedPath(task.getUserId(), task.getTaskUuid()));
            try {
                pathPolicy.verifyCompletedContent(task.getUserId(), task.getTaskUuid(), downloaded, sha256);
            } catch (BusinessException ex) {
                try {
                    pathPolicy.deleteCompletedFile(task.getUserId(), task.getTaskUuid());
                } catch (BusinessException cleanupEx) {
                    log.warn("FTP completed file cleanup failed for task {}", task.getTaskUuid());
                }
                updateState(task, FtpTaskState.FAILED, downloaded, remote.size(), task.getRetryCount(), null,
                        "LOCAL_FILE_CHANGED", "FTP 完成文件在最终校验时发生变化，请重新下载", null);
                return;
            }
            if (latest != null) {
                persistCheckpoint(latest, downloaded, remote, sha256);
            }
            updateState(task, FtpTaskState.COMPLETED, downloaded, remote.size(), task.getRetryCount(), null,
                    null, null, LocalDateTime.now());
        } catch (PauseSignal ex) {
            updateState(task, FtpTaskState.PAUSED, transfer.downloaded.get(), task.getTotalBytes(),
                    task.getRetryCount(), null, null, null, null);
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            if (transfer.cancelled) {
                updateState(task, FtpTaskState.PAUSED, transfer.downloaded.get(), task.getTotalBytes(),
                        task.getRetryCount(), null, null, null, null);
            } else {
                updateState(task, FtpTaskState.FAILED, transfer.downloaded.get(), task.getTotalBytes(),
                        task.getRetryCount(), null, "FTP_AUTH_FAILED", "FTP 服务器拒绝登录，请检查账号和密码", null);
            }
        } catch (BusinessException ex) {
            if (transfer.cancelled) {
                updateState(task, FtpTaskState.PAUSED, transfer.downloaded.get(), task.getTotalBytes(),
                        task.getRetryCount(), null, null, null, null);
            } else {
                updateState(task, FtpTaskState.FAILED, transfer.downloaded.get(), task.getTotalBytes(),
                        task.getRetryCount(), null, "TARGET_POLICY_REJECTED", ex.getMessage(), null);
            }
        } catch (IOException | RuntimeException ex) {
            if (transfer.cancelled) {
                updateState(task, FtpTaskState.PAUSED, transfer.downloaded.get(), task.getTotalBytes(),
                        task.getRetryCount(), null, null, null, null);
            } else {
                handleTransientFailure(task, transfer.downloaded.get());
            }
        } finally {
            transfer.session = null;
        }
    }

    private long transferToPart(FtpDownloadTask task,
                                ActiveTransfer transfer,
                                CommonsNetFtpClient.Session session,
                                FtpTransferCheckpoint checkpoint,
                                long checkpointRevision,
                                FileChannel output,
                                long offset,
                                CommonsNetFtpClient.RemoteIdentity remote) throws IOException {
        transfer.downloaded.set(offset);
        long lastCheckpointBytes = offset;
        long lastCheckpointNanos = System.nanoTime();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTransferDeadlineMs());
        try (InputStream input = session.retrieve(task.getRemotePath(), offset)) {
            output.position(offset);
            byte[] bytes = new byte[Math.max(4096, properties.getFileBufferBytes())];
            while (true) {
                if (transfer.cancelled) {
                    output.force(true);
                    persistCheckpoint(checkpoint, checkpointRevision, transfer.downloaded.get(), remote, null);
                    throw new PauseSignal();
                }
                if (System.nanoTime() > deadlineNanos) {
                    throw new IOException("FTP 下载超过总时限");
                }
                int read;
                try {
                    read = input.read(bytes);
                } catch (IOException ex) {
                    if (transfer.cancelled) {
                        output.force(true);
                        persistCheckpoint(checkpoint, checkpointRevision, transfer.downloaded.get(), remote, null);
                        throw new PauseSignal();
                    }
                    throw ex;
                }
                if (read < 0) {
                    break;
                }
                ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                long downloaded = transfer.downloaded.addAndGet(read);
                if (downloaded > properties.getMaxFileBytes()) {
                    throw new IOException("FTP 下载超过文件大小上限");
                }
                long now = System.nanoTime();
                if (downloaded - lastCheckpointBytes >= properties.getCheckpointBytes()
                        || Duration.ofNanos(now - lastCheckpointNanos).toMillis() >= properties.getCheckpointIntervalMs()) {
                    output.force(true);
                    persistCheckpoint(checkpoint, checkpointRevision, downloaded, remote, null);
                    checkpointRevision++;
                    lastCheckpointBytes = downloaded;
                    lastCheckpointNanos = now;
                    taskMapper.updateWorkerProgress(task.getId(), task.getUserId(), task.getExecutionEpoch(),
                            downloaded, remote.size());
                }
            }
            output.force(true);
            persistCheckpoint(checkpoint, checkpointRevision, transfer.downloaded.get(), remote, null);
        }
        session.completeTransfer();
        return transfer.downloaded.get();
    }

    private long persistCheckpoint(FtpTransferCheckpoint checkpoint,
                                   long committedBytes,
                                   CommonsNetFtpClient.RemoteIdentity remote,
                                   String sha256) {
        persistCheckpoint(checkpoint, checkpoint.getRevision(), committedBytes, remote, sha256);
        return checkpoint.getRevision() + 1;
    }

    private void persistCheckpoint(FtpTransferCheckpoint checkpoint,
                                   long expectedRevision,
                                   long committedBytes,
                                   CommonsNetFtpClient.RemoteIdentity remote,
                                   String sha256) {
        int updated = checkpointMapper.updateCas(
                checkpoint.getTaskId(), checkpoint.getUserId(), expectedRevision, committedBytes,
                remote.size(), remote.modifiedAt() != null ? remote.modifiedAt().toString() : null,
                remote.uniqueId(), sha256, LocalDateTime.now());
        if (updated == 0) {
            throw new StaleExecutionException();
        }
    }

    private String decrypt(FtpDownloadTask task,
                           FtpTaskCredential credential,
                           String field,
                           String cipherText,
                           String nonce) {
        if (cipherText == null) {
            return null;
        }
        return credentialCipher.decrypt(task.getUserId(), task.getTaskUuid(), field,
                new FtpCredentialCipher.CipherPayload(cipherText, nonce, credential.getKeyId(), credential.getCipherVersion()));
    }

    private static boolean sameRemoteIdentity(FtpTransferCheckpoint checkpoint,
                                              CommonsNetFtpClient.RemoteIdentity remote) {
        if (checkpoint.getRemoteSize() == null || remote.size() == null
                || !checkpoint.getRemoteSize().equals(remote.size())) {
            return false;
        }
        if (checkpoint.getRemoteUniqueId() != null && remote.uniqueId() != null) {
            return checkpoint.getRemoteUniqueId().equals(remote.uniqueId());
        }
        return checkpoint.getRemoteMtime() != null && remote.modifiedAt() != null
                && checkpoint.getRemoteMtime().equals(remote.modifiedAt().toString());
    }

    static boolean requiresIndependentVerification(CommonsNetFtpClient.RemoteIdentity remote,
                                                   String expectedSha256) {
        return remote.size() == null && (expectedSha256 == null || expectedSha256.isBlank());
    }

    private void handleTransientFailure(FtpDownloadTask task, long downloaded) {
        int retryCount = task.getRetryCount() + 1;
        if (retryCount > properties.getRetryMax()) {
            updateState(task, FtpTaskState.FAILED, downloaded, task.getTotalBytes(), retryCount, null,
                    "FTP_TRANSFER_FAILED", "FTP 下载多次重试后仍失败，请稍后手动继续", null);
            return;
        }
        long delay = Math.min(properties.getRetryMaxMs(),
                properties.getRetryInitialMs() * (1L << Math.min(20, retryCount - 1)));
        if (updateState(task, FtpTaskState.RETRY_WAIT, downloaded, task.getTotalBytes(), retryCount,
                LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(delay)),
                "FTP_TRANSFER_INTERRUPTED", "FTP 连接暂时中断，系统将进行有限重试", null)) {
            scheduleSubmit(task.getUserId(), task.getTaskUuid(), delay);
        }
    }

    private void scheduleSubmit(long userId, String taskUuid, long delayMs) {
        retries.schedule(() -> {
            try {
                submit(userId, taskUuid);
            } catch (BusinessException ex) {
                FtpDownloadTask current = taskMapper.findOwnedByUuid(taskUuid, userId);
                if (current != null && (FtpTaskState.RETRY_WAIT.name().equals(current.getStatus())
                        || FtpTaskState.QUEUED.name().equals(current.getStatus()))) {
                    taskMapper.updateStateCas(taskUuid, userId, current.getRevision(), FtpTaskState.FAILED.name(),
                            "QUEUE_FULL", "FTP 下载队列持续繁忙，请稍后手动继续");
                }
                log.warn("FTP task queue rejected for task {}", taskUuid);
            }
        }, Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
    }

    private boolean updateState(FtpDownloadTask task,
                                FtpTaskState state,
                                long downloaded,
                                Long total,
                                int retryCount,
                                LocalDateTime nextRetryAt,
                                String errorCode,
                                String errorMessage,
                                LocalDateTime completedAt) {
        boolean updated = taskMapper.updateWorkerState(task.getId(), task.getUserId(), task.getExecutionEpoch(), state.name(),
                Math.max(0L, downloaded), total, retryCount, nextRetryAt, errorCode,
                limit(errorMessage, 500), completedAt) > 0;
        if (!updated && state != FtpTaskState.PAUSED) {
            settlePausing(task, downloaded, total);
        }
        return updated;
    }

    private void settlePausing(FtpDownloadTask task, long downloaded, Long total) {
        FtpDownloadTask current = taskMapper.findOwnedByUuid(task.getTaskUuid(), task.getUserId());
        if (current != null && FtpTaskState.PAUSING.name().equals(current.getStatus())
                && task.getExecutionEpoch().equals(current.getExecutionEpoch())) {
            taskMapper.updateWorkerState(task.getId(), task.getUserId(), task.getExecutionEpoch(),
                    FtpTaskState.PAUSED.name(), Math.max(0L, downloaded), total,
                    current.getRetryCount(), null, null, null, null);
        }
    }

    private static long safeDownloaded(FtpDownloadTask task) {
        return task.getDownloadedBytes() != null ? task.getDownloadedBytes() : 0L;
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String sha256(FileChannel channel, long expectedBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            channel.force(true);
            channel.position(0L);
            ByteBuffer buffer = ByteBuffer.allocate(262144);
            long hashed = 0L;
            int read;
            while ((read = channel.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                hashed += read;
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            if (hashed != expectedBytes) {
                throw new IOException("FTP 临时文件在完整性校验期间发生变化");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    private static void moveCompleted(Path part, Path completed) throws IOException {
        try {
            Files.move(part, completed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("FTP 存储目录不支持原子完成文件替换", ex);
        }
    }

    @PreDestroy
    public void close() {
        for (ActiveTransfer transfer : active.values()) {
            transfer.cancelled = true;
            if (transfer.session != null) {
                transfer.session.abort();
            }
        }
        retries.shutdownNow();
        workers.shutdownNow();
    }

    private static final class ActiveTransfer {
        private final AtomicLong downloaded = new AtomicLong();
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean cancelled;
        private volatile CommonsNetFtpClient.Session session;
    }

    private static final class PauseSignal extends RuntimeException {
    }

    private static final class StaleExecutionException extends RuntimeException {
    }
}
