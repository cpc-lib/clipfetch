package cc.ivera.ftp.application;

import cc.ivera.ftp.domain.FtpAuthMode;
import cc.ivera.ftp.domain.FtpDownloadTask;
import cc.ivera.ftp.domain.FtpDownloadTaskMapper;
import cc.ivera.ftp.domain.FtpProtocol;
import cc.ivera.ftp.domain.FtpRequestValidator;
import cc.ivera.ftp.domain.FtpTaskCredential;
import cc.ivera.ftp.domain.FtpTaskCredentialMapper;
import cc.ivera.ftp.domain.FtpTaskState;
import cc.ivera.ftp.domain.FtpTransferCheckpoint;
import cc.ivera.ftp.domain.FtpTransferCheckpointMapper;
import cc.ivera.ftp.infrastructure.CommonsNetFtpClient;
import cc.ivera.ftp.infrastructure.FtpCredentialCipher;
import cc.ivera.ftp.infrastructure.FtpPathPolicy;
import cc.ivera.ftp.infrastructure.FtpProperties;
import cc.ivera.ftp.infrastructure.FtpTargetPolicy;
import cc.ivera.ftp.interfaces.FtpDtos.BatchCreateTaskItem;
import cc.ivera.ftp.interfaces.FtpDtos.ConnectionTestRequest;
import cc.ivera.ftp.interfaces.FtpDtos.ConnectionTestResponse;
import cc.ivera.ftp.interfaces.FtpDtos.CreateBatchTasksRequest;
import cc.ivera.ftp.interfaces.FtpDtos.CreateDirectoryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.CreateDirectoryResponse;
import cc.ivera.ftp.interfaces.FtpDtos.CreateTaskRequest;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteBatchEntriesRequest;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteBatchEntriesResponse;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteBatchItem;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteEntryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.DeleteEntryResponse;
import cc.ivera.ftp.interfaces.FtpDtos.DirectoryListingResponse;
import cc.ivera.ftp.interfaces.FtpDtos.FileUploadResponse;
import cc.ivera.ftp.interfaces.FtpDtos.ListDirectoryRequest;
import cc.ivera.ftp.interfaces.FtpDtos.MoveBatchEntriesRequest;
import cc.ivera.ftp.interfaces.FtpDtos.MoveBatchEntriesResponse;
import cc.ivera.ftp.interfaces.FtpDtos.MovedEntryItem;
import cc.ivera.ftp.interfaces.FtpDtos.RemoteEntryView;
import cc.ivera.ftp.interfaces.FtpDtos.StreamDownloadRequest;
import cc.ivera.ftp.interfaces.FtpDtos.TaskPageResponse;
import cc.ivera.ftp.interfaces.FtpDtos.TaskView;
import cc.ivera.ftp.interfaces.FtpDtos.UploadedFileItem;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class FtpTaskService {

    private final FtpProperties properties;
    private final FtpTargetPolicy targetPolicy;
    private final CommonsNetFtpClient ftpClient;
    private final FtpCredentialCipher credentialCipher;
    private final FtpPathPolicy pathPolicy;
    private final FtpDownloadTaskMapper taskMapper;
    private final FtpTaskCredentialMapper credentialMapper;
    private final FtpTransferCheckpointMapper checkpointMapper;
    private final FtpTransferCoordinator coordinator;
    private final TransactionTemplate transactionTemplate;
    private final Map<Long, Long> lastConnectionTestNanos = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnectionTests = new AtomicInteger();

    public TaskView create(long userId, CreateTaskRequest body) {
        requireConfigured();
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedRequest request = FtpRequestValidator.validate(
                body.protocol(), body.host(), port, body.remotePath(), body.authMode(),
                body.username(), body.password(), body.displayName(), body.expectedSha256());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());

        String taskUuid = transactionTemplate.execute(status -> {
            if (taskMapper.countActiveOwned(userId) >= properties.getMaxActiveTasksPerUser()) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                        "当前进行中的 FTP 下载任务已达上限（最多 " + properties.getMaxActiveTasksPerUser() + " 个）");
            }
            LocalDateTime now = LocalDateTime.now();
            String uuid = UUID.randomUUID().toString();
            FtpDownloadTask task = FtpDownloadTask.builder()
                    .taskUuid(uuid)
                    .userId(userId)
                    .protocol(request.protocol().name())
                    .host(request.host())
                    .port(request.port())
                    .remotePath(request.remotePath())
                    .displayName(request.displayName())
                    .status(FtpTaskState.QUEUED.name())
                    .downloadedBytes(0L)
                    .expectedSha256(request.expectedSha256())
                    .revision(1L)
                    .executionEpoch(0L)
                    .retryCount(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .deleted(0L)
                    .build();
            taskMapper.insertTask(task);

            FtpCredentialCipher.CipherPayload username = request.authMode() == FtpAuthMode.CREDENTIALS
                    ? credentialCipher.encrypt(userId, uuid, "username", request.username()) : null;
            FtpCredentialCipher.CipherPayload password = request.authMode() == FtpAuthMode.CREDENTIALS
                    ? credentialCipher.encrypt(userId, uuid, "password", request.password()) : null;
            FtpTaskCredential credential = FtpTaskCredential.builder()
                    .taskId(task.getId())
                    .userId(userId)
                    .authMode(request.authMode().name())
                    .encryptedUsername(username != null ? username.cipherText() : null)
                    .encryptedPassword(password != null ? password.cipherText() : null)
                    .usernameNonce(username != null ? username.nonce() : null)
                    .passwordNonce(password != null ? password.nonce() : null)
                    .keyId(properties.getCredentialKeyId())
                    .cipherVersion(1)
                    .createdAt(now)
                    .updatedAt(now)
                    .deleted(0L)
                    .build();
            credentialMapper.insertCredential(credential);

            FtpTransferCheckpoint checkpoint = FtpTransferCheckpoint.builder()
                    .taskId(task.getId())
                    .userId(userId)
                    .localKey(pathPolicy.localKey(userId, uuid))
                    .committedBytes(0L)
                    .revision(1L)
                    .createdAt(now)
                    .updatedAt(now)
                    .deleted(0L)
                    .build();
            checkpointMapper.insertCheckpoint(checkpoint);
            return uuid;
        });
        if (taskUuid == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "FTP 任务创建失败");
        }
        submitOrFail(userId, taskUuid, 1L);
        return get(userId, taskUuid);
    }

    public List<TaskView> createBatch(long userId, CreateBatchTasksRequest body) {
        requireConfigured();
        if (body.items() == null || body.items().isEmpty()) {
            throw new BusinessException("请至少选择一个待下载的远端文件");
        }
        int port = body.port() != null ? body.port() : 21;
        ensureProtocolEnabled(body.protocol().name());
        targetPolicy.authorize(body.host(), port);

        List<FtpRequestValidator.ValidatedRequest> validated = new ArrayList<>(body.items().size());
        for (BatchCreateTaskItem item : body.items()) {
            validated.add(FtpRequestValidator.validate(
                    body.protocol(), body.host(), port, item.remotePath(), body.authMode(),
                    body.username(), body.password(), item.displayName(), item.expectedSha256()));
        }

        List<String> taskUuids = transactionTemplate.execute(status -> {
            int currentActive = taskMapper.countActiveOwned(userId);
            if (currentActive + validated.size() > properties.getMaxActiveTasksPerUser()) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                        "创建后进行中的任务数将超出上限（当前 " + currentActive + " 个，上限 "
                                + properties.getMaxActiveTasksPerUser() + " 个）");
            }
            LocalDateTime now = LocalDateTime.now();
            List<String> createdUuids = new ArrayList<>(validated.size());
            for (FtpRequestValidator.ValidatedRequest request : validated) {
                String uuid = UUID.randomUUID().toString();
                FtpDownloadTask task = FtpDownloadTask.builder()
                        .taskUuid(uuid)
                        .userId(userId)
                        .protocol(request.protocol().name())
                        .host(request.host())
                        .port(request.port())
                        .remotePath(request.remotePath())
                        .displayName(request.displayName())
                        .status(FtpTaskState.QUEUED.name())
                        .downloadedBytes(0L)
                        .expectedSha256(request.expectedSha256())
                        .revision(1L)
                        .executionEpoch(0L)
                        .retryCount(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .deleted(0L)
                        .build();
                taskMapper.insertTask(task);

                FtpCredentialCipher.CipherPayload username = request.authMode() == FtpAuthMode.CREDENTIALS
                        ? credentialCipher.encrypt(userId, uuid, "username", request.username()) : null;
                FtpCredentialCipher.CipherPayload password = request.authMode() == FtpAuthMode.CREDENTIALS
                        ? credentialCipher.encrypt(userId, uuid, "password", request.password()) : null;
                FtpTaskCredential credential = FtpTaskCredential.builder()
                        .taskId(task.getId())
                        .userId(userId)
                        .authMode(request.authMode().name())
                        .encryptedUsername(username != null ? username.cipherText() : null)
                        .encryptedPassword(password != null ? password.cipherText() : null)
                        .usernameNonce(username != null ? username.nonce() : null)
                        .passwordNonce(password != null ? password.nonce() : null)
                        .keyId(properties.getCredentialKeyId())
                        .cipherVersion(1)
                        .createdAt(now)
                        .updatedAt(now)
                        .deleted(0L)
                        .build();
                credentialMapper.insertCredential(credential);

                FtpTransferCheckpoint checkpoint = FtpTransferCheckpoint.builder()
                        .taskId(task.getId())
                        .userId(userId)
                        .localKey(pathPolicy.localKey(userId, uuid))
                        .committedBytes(0L)
                        .revision(1L)
                        .createdAt(now)
                        .updatedAt(now)
                        .deleted(0L)
                        .build();
                checkpointMapper.insertCheckpoint(checkpoint);
                createdUuids.add(uuid);
            }
            return createdUuids;
        });

        if (taskUuids == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "批量创建 FTP 任务失败");
        }
        for (String uuid : taskUuids) {
            submitOrFail(userId, uuid, 1L);
        }
        return taskUuids.stream().map(uuid -> get(userId, uuid)).toList();
    }

    public ConnectionTestResponse testConnection(long userId, CreateTaskRequest body) {
        return testConnection(userId, new ConnectionTestRequest(
                body.protocol(), body.host(), body.port(), body.remotePath(),
                body.authMode(), body.username(), body.password(),
                body.displayName(), body.expectedSha256()));
    }

    public ConnectionTestResponse testConnection(long userId, ConnectionTestRequest body) {
        requireConfigured();
        enforceConnectionTestCooldown(userId);
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedConnectionTestRequest request =
                FtpRequestValidator.validateConnectionTestRequest(
                        body.protocol(), body.host(), port, body.remotePath(), body.authMode(),
                        body.username(), body.password(), body.displayName(), body.expectedSha256());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());
        if (activeConnectionTests.incrementAndGet() > Math.max(1, properties.getMaxConcurrent())) {
            activeConnectionTests.decrementAndGet();
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "FTP 连接测试并发已达上限，请稍后再试");
        }
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                request.protocol(), request.host(), request.port(), request.authMode(),
                request.username(), request.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            if (request.remotePath() == null) {
                return new ConnectionTestResponse(true, null, null, "FTP 服务器连接与登录验证成功");
            }
            if (request.directoryOnly()) {
                if (!session.checkDirectoryExists(request.remotePath())) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "FTP 服务器连接成功，但远端目录不存在或无权访问：" + request.remotePath());
                }
                return new ConnectionTestResponse(true, null, null,
                        "FTP 服务器连接与远端目录检查成功：" + request.remotePath());
            }
            CommonsNetFtpClient.RemoteIdentity identity = session.inspect(request.remotePath());
            return new ConnectionTestResponse(true, identity.size(),
                    identity.modifiedAt() != null ? identity.modifiedAt().toString() : null,
                    "FTP 服务器连接与文件检查成功");
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("FTP 连接或文件检查", request.host(), request.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    public DirectoryListingResponse listDirectory(long userId, ListDirectoryRequest body) {
        requireConfigured();
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedDirectoryRequest request = FtpRequestValidator.validateDirectoryRequest(
                body.protocol(), body.host(), port, body.remotePath(),
                body.authMode(), body.username(), body.password());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                request.protocol(), request.host(), request.port(),
                request.authMode(), request.username(), request.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            List<RemoteEntryView> entries = session.listDirectory(request.remoteDirectoryPath()).stream()
                    .map(entry -> new RemoteEntryView(
                            entry.name(),
                            entry.path(),
                            entry.type(),
                            entry.size(),
                            entry.modifiedAt() != null ? entry.modifiedAt().toString() : null))
                    .toList();
            return new DirectoryListingResponse(
                    request.remoteDirectoryPath(),
                    FtpRequestValidator.parentDirectoryPath(request.remoteDirectoryPath()),
                    entries);
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("读取 FTP 远端目录", request.host(), request.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    public CreateDirectoryResponse createDirectory(long userId, CreateDirectoryRequest body) {
        requireConfigured();
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedCreateDirectoryRequest request = FtpRequestValidator.validateCreateDirectoryRequest(
                body.protocol(), body.host(), port, body.parentPath(), body.directoryName(),
                body.authMode(), body.username(), body.password());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                request.protocol(), request.host(), request.port(),
                request.authMode(), request.username(), request.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            session.makeDirectory(request.targetDirectoryPath());
            return new CreateDirectoryResponse(
                    request.targetDirectoryPath(),
                    request.directoryName(),
                    "已创建远端文件夹：" + request.targetDirectoryPath());
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("创建 FTP 远端文件夹", request.host(), request.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    /**
     * 仅删除远端服务器上的文件或文件夹（非空文件夹将递归删除内部内容）；不写、不改、不删本地下载任务记录与已下载文件。
     */
    public DeleteEntryResponse deleteEntry(long userId, DeleteEntryRequest body) {
        requireConfigured();
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedDeleteEntryRequest request = FtpRequestValidator.validateDeleteEntryRequest(
                body.protocol(), body.host(), port, body.remotePath(), body.entryType(),
                body.authMode(), body.username(), body.password());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                request.protocol(), request.host(), request.port(),
                request.authMode(), request.username(), request.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            if ("DIRECTORY".equals(request.entryType())) {
                session.removeDirectory(request.targetPath());
            } else {
                session.deleteFile(request.targetPath());
            }
            return new DeleteEntryResponse(
                    request.targetPath(),
                    request.entryName(),
                    "已删除远端" + ("DIRECTORY".equals(request.entryType()) ? "文件夹：" : "文件：")
                            + request.targetPath());
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("删除 FTP 远端条目", request.host(), request.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    /**
     * 在单次 FTP 会话内批量删除远端服务器上的文件或文件夹（非空文件夹将递归删除内部内容）；
     * 避免多条目循环建立连接的性能与网络开销。
     */
    public DeleteBatchEntriesResponse deleteEntriesBatch(long userId, DeleteBatchEntriesRequest body) {
        requireConfigured();
        if (body.items() == null || body.items().isEmpty()) {
            throw new BusinessException("请至少选择一个待删除的远端条目");
        }
        int port = body.port() != null ? body.port() : 21;
        List<FtpRequestValidator.ValidatedDeleteEntryRequest> validatedItems = new ArrayList<>(body.items().size());
        for (DeleteBatchItem item : body.items()) {
            validatedItems.add(FtpRequestValidator.validateDeleteEntryRequest(
                    body.protocol(), body.host(), port, item.remotePath(), item.entryType(),
                    body.authMode(), body.username(), body.password()));
        }
        FtpRequestValidator.ValidatedDeleteEntryRequest first = validatedItems.get(0);
        ensureProtocolEnabled(first.protocol().name());
        targetPolicy.authorize(first.host(), first.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                first.protocol(), first.host(), first.port(),
                first.authMode(), first.username(), first.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            List<String> deletedPaths = new ArrayList<>(validatedItems.size());
            for (FtpRequestValidator.ValidatedDeleteEntryRequest item : validatedItems) {
                if ("DIRECTORY".equals(item.entryType())) {
                    session.removeDirectory(item.targetPath());
                } else {
                    session.deleteFile(item.targetPath());
                }
                deletedPaths.add(item.targetPath());
            }
            return new DeleteBatchEntriesResponse(
                    deletedPaths.size(),
                    deletedPaths,
                    "已成功批量删除远端 " + deletedPaths.size() + " 个条目"
            );
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("批量删除 FTP 远端条目", first.host(), first.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    /**
     * 在单次 FTP 会话内将多个文件或文件夹批量移动至指定的远端目标新目录；
     * 若目标目录不存在，将自动为其递归创建。
     */
    public MoveBatchEntriesResponse moveEntriesBatch(long userId, MoveBatchEntriesRequest body) {
        requireConfigured();
        if (body.sourcePaths() == null || body.sourcePaths().isEmpty()) {
            throw new BusinessException("请至少选择一个待移动的远端条目");
        }
        int port = body.port() != null ? body.port() : 21;
        String normTargetDir = FtpRequestValidator.normalizeDirectoryPath(body.targetDirectory());
        List<FtpRequestValidator.ValidatedMoveItem> items = new ArrayList<>(body.sourcePaths().size());
        for (String sourcePath : body.sourcePaths()) {
            items.add(FtpRequestValidator.validateMoveItem(sourcePath, normTargetDir));
        }

        FtpProtocol protocol = body.protocol();
        String host = body.host();
        ensureProtocolEnabled(protocol.name());
        targetPolicy.authorize(host, port);
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                protocol, host, port,
                body.authMode(), body.username(), body.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            session.makeDirectories(normTargetDir);

            List<MovedEntryItem> movedEntries = new ArrayList<>(items.size());
            for (FtpRequestValidator.ValidatedMoveItem item : items) {
                if (item.sourcePath().equals(item.targetPath())) {
                    continue;
                }
                session.rename(item.sourcePath(), item.targetPath());
                movedEntries.add(new MovedEntryItem(item.sourcePath(), item.targetPath(), item.fileName()));
            }
            return new MoveBatchEntriesResponse(
                    movedEntries.size(),
                    normTargetDir,
                    movedEntries,
                    "已成功移动 " + movedEntries.size() + " 个条目至 " + normTargetDir
            );
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("批量移动 FTP 远端条目", host, port, ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    public FileUploadResponse uploadFile(long userId,
                                         cc.ivera.ftp.domain.FtpProtocol protocol,
                                         String host,
                                         Integer port,
                                         String remoteDirectory,
                                         String remoteFileName,
                                         FtpAuthMode authMode,
                                         String username,
                                         String password,
                                         MultipartFile file) {
        if (file == null) {
            throw new BusinessException("请选择要上传的文件");
        }
        return uploadFiles(userId, protocol, host, port, remoteDirectory,
                remoteFileName, authMode, username, password, List.of(file));
    }

    public FileUploadResponse uploadFiles(long userId,
                                          cc.ivera.ftp.domain.FtpProtocol protocol,
                                          String host,
                                          Integer port,
                                          String remoteDirectory,
                                          String remoteFileName,
                                          FtpAuthMode authMode,
                                          String username,
                                          String password,
                                          List<MultipartFile> files) {
        requireConfigured();
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        int actualPort = port != null ? port : 21;
        boolean singleFile = files.size() == 1;
        List<FtpRequestValidator.ValidatedUploadRequest> validatedRequests = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null) {
                throw new BusinessException("请选择要上传的文件");
            }
            long fileSize = file.getSize();
            if (fileSize > properties.getMaxFileBytes()) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过管理员配置的大小上限");
            }
            String candidateFileName = (singleFile && remoteFileName != null && !remoteFileName.isBlank())
                    ? remoteFileName.trim()
                    : file.getOriginalFilename();
            validatedRequests.add(FtpRequestValidator.validateUploadRequest(
                    protocol, host, actualPort, remoteDirectory, candidateFileName,
                    authMode, username, password));
        }

        FtpRequestValidator.ValidatedUploadRequest first = validatedRequests.get(0);
        ensureProtocolEnabled(first.protocol().name());
        targetPolicy.authorize(first.host(), first.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                first.protocol(), first.host(), first.port(),
                first.authMode(), first.username(), first.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            List<UploadedFileItem> uploadedItems = new ArrayList<>(files.size());
            long totalUploadedBytes = 0L;
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                FtpRequestValidator.ValidatedUploadRequest req = validatedRequests.get(i);
                try (InputStream input = file.getInputStream()) {
                    long uploaded = session.store(req.targetRemotePath(), input, properties.getMaxFileBytes());
                    totalUploadedBytes += uploaded;
                    uploadedItems.add(new UploadedFileItem(req.targetRemotePath(), req.fileName(), uploaded));
                }
            }
            if (uploadedItems.size() == 1) {
                UploadedFileItem item = uploadedItems.get(0);
                return new FileUploadResponse(
                        item.remotePath(),
                        item.fileName(),
                        item.uploadedBytes(),
                        "文件已上传至 " + item.remotePath(),
                        1,
                        uploadedItems);
            }
            return new FileUploadResponse(
                    first.remoteDirectoryPath(),
                    uploadedItems.get(0).fileName() + " 等 " + uploadedItems.size() + " 个文件",
                    totalUploadedBytes,
                    "已成功上传 " + uploadedItems.size() + " 个文件至 " + first.remoteDirectoryPath(),
                    uploadedItems.size(),
                    uploadedItems);
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    formatFtpIoError("上传文件到 FTP 服务器", first.host(), first.port(), ex));
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    /**
     * 直接从 FTP/FTPS 服务器流式拉取文件并写入 HTTP 响应，浏览器直接保存到本地目录；
     * 服务端零落盘、不产生本地临时或持久文件，不创建后台下载任务。
     */
    public void streamRemoteFile(long userId, StreamDownloadRequest body, HttpServletResponse response) {
        requireConfigured();
        int port = body.port() != null ? body.port() : 21;
        FtpRequestValidator.ValidatedStreamDownloadRequest request = FtpRequestValidator.validateStreamDownloadRequest(
                body.protocol(), body.host(), port, body.remotePath(),
                body.authMode(), body.username(), body.password());
        ensureProtocolEnabled(request.protocol().name());
        targetPolicy.authorize(request.host(), request.port());
        acquireInteractiveSlot();
        CommonsNetFtpClient.ConnectionRequest connection = new CommonsNetFtpClient.ConnectionRequest(
                request.protocol(), request.host(), request.port(),
                request.authMode(), request.username(), request.password());
        try (CommonsNetFtpClient.Session session = ftpClient.connect(connection)) {
            CommonsNetFtpClient.RemoteIdentity identity = session.inspect(request.remotePath());
            Long totalBytes = identity != null ? identity.size() : null;

            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(request.fileName(), StandardCharsets.UTF_8).build().toString());
            if (totalBytes != null && totalBytes >= 0) {
                response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(totalBytes));
            }

            try (InputStream input = session.retrieve(request.remotePath(), 0L);
                 OutputStream output = response.getOutputStream()) {
                byte[] buffer = new byte[Math.max(4096, properties.getFileBufferBytes())];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                output.flush();
            }
            session.completeTransfer();
        } catch (CommonsNetFtpClient.FtpAuthenticationException ex) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FTP 服务器拒绝登录，请检查账号和密码");
        } catch (IOException ex) {
            if (!response.isCommitted()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        formatFtpIoError("流式下载 FTP 远端文件", request.host(), request.port(), ex));
            } else {
                log.warn("FTP 远端文件流式下载传输中断: {} - {}", request.remotePath(), ex.getMessage());
            }
        } finally {
            activeConnectionTests.decrementAndGet();
        }
    }

    private static String formatFtpIoError(String actionLabel, String host, int port, IOException ex) {
        if (ex instanceof java.net.ConnectException) {
            return actionLabel + "失败：无法连接到 FTP 服务器 (" + host + ":" + port
                    + ")，连接被拒绝，请确认目标 FTP/FTPS 服务已启动且端口正确";
        }
        if (ex instanceof java.net.SocketTimeoutException) {
            return actionLabel + "失败：连接 FTP 服务器 (" + host + ":" + port + ") 超时，请检查网络或防火墙设置";
        }
        String detail = ex.getMessage();
        return detail == null || detail.isBlank()
                ? actionLabel + "失败，请确认服务器、端口和远端路径"
                : actionLabel + "失败：" + detail;
    }

    private void acquireInteractiveSlot() {
        if (activeConnectionTests.incrementAndGet() > Math.max(1, properties.getMaxConcurrent())) {
            activeConnectionTests.decrementAndGet();
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "FTP 交互操作并发已达上限，请稍后再试");
        }
    }

    public TaskView get(long userId, String taskUuid) {
        return view(requireOwned(userId, taskUuid));
    }

    public TaskPageResponse list(long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(30, size));
        int total = taskMapper.countOwned(userId);
        List<TaskView> items = total == 0 ? List.of() : taskMapper
                .listOwned(userId, safeSize, (safePage - 1) * safeSize)
                .stream().map(this::view).toList();
        return new TaskPageResponse(items, safePage, safeSize, total);
    }

    public TaskView pause(long userId, String taskUuid, long expectedRevision) {
        FtpDownloadTask task = requireOwned(userId, taskUuid);
        requireRevision(task, expectedRevision);
        FtpTaskState state = FtpTaskState.valueOf(task.getStatus());
        if (state == FtpTaskState.PAUSED || state == FtpTaskState.PAUSING) {
            return view(task);
        }
        if (state == FtpTaskState.COMPLETED || state == FtpTaskState.VERIFYING || state == FtpTaskState.NEEDS_RESTART
                || state == FtpTaskState.NEEDS_VERIFICATION
                || state == FtpTaskState.BLOCKED_CREDENTIALS) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前 FTP 任务状态不能暂停");
        }
        FtpTaskState next = state == FtpTaskState.QUEUED || state == FtpTaskState.RETRY_WAIT
                || state == FtpTaskState.FAILED ? FtpTaskState.PAUSED : FtpTaskState.PAUSING;
        if (taskMapper.updateStateCas(taskUuid, userId, expectedRevision, next.name(), null, null) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
        }
        coordinator.cancel(taskUuid);
        return get(userId, taskUuid);
    }

    public TaskView resume(long userId, String taskUuid, long expectedRevision) {
        FtpDownloadTask task = requireOwned(userId, taskUuid);
        requireRevision(task, expectedRevision);
        if (!(FtpTaskState.PAUSED.name().equals(task.getStatus())
                || FtpTaskState.FAILED.name().equals(task.getStatus()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "只有已暂停或失败的 FTP 任务可以继续");
        }
        if (taskMapper.countActiveOwned(userId) >= properties.getMaxActiveTasksPerUser()) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "当前进行中的 FTP 下载任务已达上限");
        }
        if (taskMapper.updateStateCas(taskUuid, userId, expectedRevision, FtpTaskState.QUEUED.name(), null, null) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
        }
        submitOrFail(userId, taskUuid, expectedRevision + 1);
        return get(userId, taskUuid);
    }

    public TaskView restart(long userId, String taskUuid, long expectedRevision) {
        FtpDownloadTask task = requireOwned(userId, taskUuid);
        requireRevision(task, expectedRevision);
        if (!(FtpTaskState.NEEDS_RESTART.name().equals(task.getStatus())
                || FtpTaskState.FAILED.name().equals(task.getStatus())
                || FtpTaskState.PAUSED.name().equals(task.getStatus()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前 FTP 任务不能从头重下");
        }
        if (taskMapper.updateStateCas(taskUuid, userId, expectedRevision, FtpTaskState.PAUSING.name(),
                task.getLastErrorCode(), task.getLastErrorMessage()) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
        }
        long claimedRevision = expectedRevision + 1;
        coordinator.cancel(taskUuid);
        try {
            pathPolicy.deleteTaskDirectory(userId, taskUuid);
            transactionTemplate.executeWithoutResult(status -> {
                FtpTransferCheckpoint checkpoint = checkpointMapper.findOwnedByTaskId(task.getId(), userId);
                if (checkpoint == null || checkpointMapper.updateCas(task.getId(), userId, checkpoint.getRevision(),
                        0L, null, null, null, null, null) == 0) {
                    throw new BusinessException(HttpStatus.CONFLICT, "FTP 检查点已变化，请刷新后重试");
                }
                if (taskMapper.resetForRestart(taskUuid, userId, claimedRevision) == 0) {
                    throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
                }
            });
        } catch (RuntimeException ex) {
            taskMapper.updateStateCas(taskUuid, userId, claimedRevision, task.getStatus(),
                    task.getLastErrorCode(), task.getLastErrorMessage());
            throw ex;
        }
        submitOrFail(userId, taskUuid, claimedRevision + 1);
        return get(userId, taskUuid);
    }

    public void delete(long userId, String taskUuid, long expectedRevision) {
        FtpDownloadTask task = requireOwned(userId, taskUuid);
        requireRevision(task, expectedRevision);
        if (taskMapper.updateStateCas(taskUuid, userId, expectedRevision, FtpTaskState.DELETING.name(),
                task.getLastErrorCode(), task.getLastErrorMessage()) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
        }
        long deletingRevision = expectedRevision + 1;
        boolean filesDeleted = false;
        try {
            if (!coordinator.cancelAndAwait(taskUuid, properties.getTransferIdleTimeoutMs())) {
                throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务仍在停止中，请稍后重试删除");
            }
            pathPolicy.deleteTaskDirectory(userId, taskUuid);
            filesDeleted = true;
            transactionTemplate.executeWithoutResult(status -> {
                credentialMapper.logicalDeleteOwned(task.getId(), userId);
                checkpointMapper.logicalDeleteOwned(task.getId(), userId);
                if (taskMapper.logicalDeleteOwned(task.getId(), taskUuid, userId, deletingRevision) == 0) {
                    throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务状态已变化，请刷新后重试");
                }
            });
        } catch (RuntimeException ex) {
            taskMapper.updateStateCas(taskUuid, userId, deletingRevision,
                    filesDeleted ? FtpTaskState.FAILED.name() : task.getStatus(),
                    filesDeleted ? "DELETE_INCOMPLETE" : task.getLastErrorCode(),
                    filesDeleted ? "FTP 任务文件已清理，但数据库删除未完成，请重试删除" : task.getLastErrorMessage());
            throw ex;
        }
    }

    private void enforceConnectionTestCooldown(long userId) {
        long now = System.nanoTime();
        long cooldown = Math.max(1L, properties.getConnectionTestCooldownMs()) * 1_000_000L;
        Long previous = lastConnectionTestNanos.put(userId, now);
        if (previous != null && now - previous < cooldown) {
            lastConnectionTestNanos.put(userId, previous);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "FTP 连接测试过于频繁，请稍后再试");
        }
    }

    private void submitOrFail(long userId, String taskUuid, long expectedRevision) {
        try {
            coordinator.submit(userId, taskUuid);
        } catch (BusinessException ex) {
            int updated = taskMapper.updateStateCas(taskUuid, userId, expectedRevision,
                    FtpTaskState.FAILED.name(), "QUEUE_FULL", ex.getMessage());
            if (updated == 0) {
                log.warn("FTP queue rejection could not update task state: {}", taskUuid);
            }
            throw ex;
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FTP 下载功能未启用或配置不完整");
        }
    }

    private void ensureProtocolEnabled(String protocol) {
        if ("FTP".equals(protocol) && !properties.isAllowPlaintext()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "普通 FTP 明文传输未由管理员启用，请使用显式 FTPS");
        }
    }

    private FtpDownloadTask requireOwned(long userId, String taskUuid) {
        FtpDownloadTask task = taskUuid == null ? null : taskMapper.findOwnedByUuid(taskUuid, userId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FTP 下载任务不存在");
        }
        return task;
    }

    private static void requireRevision(FtpDownloadTask task, long expectedRevision) {
        if (task.getRevision() == null || task.getRevision() != expectedRevision) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 任务版本已变化，请刷新后重试");
        }
    }

    private TaskView view(FtpDownloadTask task) {
        long downloaded = task.getDownloadedBytes() != null ? task.getDownloadedBytes() : 0L;
        double percent = task.getTotalBytes() != null && task.getTotalBytes() > 0
                ? Math.min(100.0, Math.round(downloaded * 10000.0 / task.getTotalBytes()) / 100.0)
                : 0.0;
        boolean downloadable = FtpTaskState.COMPLETED.name().equals(task.getStatus());
        int port = task.getPort() != null ? task.getPort() : 21;
        long rev = task.getRevision() != null ? task.getRevision() : 1L;
        int retries = task.getRetryCount() != null ? task.getRetryCount() : 0;
        return new TaskView(task.getTaskUuid(), task.getProtocol(), task.getHost(), port,
                task.getDisplayName(), task.getStatus(), task.getTotalBytes(), downloaded, percent,
                rev, retries, task.getLastErrorCode(), task.getLastErrorMessage(),
                downloadable, task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt());
    }
}
