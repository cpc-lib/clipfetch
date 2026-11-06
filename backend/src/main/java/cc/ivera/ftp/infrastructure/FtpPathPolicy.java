package cc.ivera.ftp.infrastructure;

import cc.ivera.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
public class FtpPathPolicy {

    private final Path storageRoot;

    @Autowired
    public FtpPathPolicy(FtpProperties properties) {
        this(properties.getStorageRoot());
    }

    FtpPathPolicy(String storageRoot) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalStateException("FTP 存储根目录未配置");
        }
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public String localKey(long userId, String taskUuid) {
        validateIdentifiers(userId, taskUuid);
        return "users/" + userId + "/tasks/" + taskUuid + "/download.part";
    }

    public Path preparePartPath(long userId, String taskUuid) {
        try {
            Path part = verifiedTaskDirectory(userId, taskUuid, true).resolve("download.part");
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(part))) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 临时文件不是受控普通文件");
            }
            return part;
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 FTP 任务存储目录：" + ex.getMessage());
        }
    }

    public Path completedPath(long userId, String taskUuid) {
        try {
            return verifiedTaskDirectory(userId, taskUuid, false).resolve("completed.bin");
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法核验 FTP 任务存储目录：" + ex.getMessage());
        }
    }

    public OpenPartFile openPartFile(long userId, String taskUuid) {
        Path part = preparePartPath(userId, taskUuid);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attributes = Files.readAttributes(
                    part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("FTP 临时文件不是普通文件");
            }
            return new OpenPartFile(part, channel, attributes.fileKey());
        } catch (IOException ex) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeEx) {
                    ex.addSuppressed(closeEx);
                }
            }
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "无法安全打开 FTP 临时文件：" + ex.getMessage());
        }
    }

    public Path verifiedPartFile(long userId, String taskUuid, Object expectedFileKey) {
        Path expected = preparePartPath(userId, taskUuid);
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    expected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || (expectedFileKey != null && !Objects.equals(attributes.fileKey(), expectedFileKey))) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 临时文件在下载期间被替换");
            }
            return expected;
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "无法核验 FTP 临时文件：" + ex.getMessage());
        }
    }

    public Path verifiedCompletedFile(long userId, String taskUuid) {
        validateIdentifiers(userId, taskUuid);
        try {
            Path candidate = verifiedTaskDirectory(userId, taskUuid, false).resolve("completed.bin");
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(HttpStatus.CONFLICT, "FTP 已完成文件不存在或不是普通文件");
            }
            Path realFile = candidate.toRealPath();
            if (!realFile.equals(candidate.toAbsolutePath().normalize())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 文件路径越界");
            }
            return realFile;
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法核验 FTP 已完成文件：" + ex.getMessage());
        }
    }

    public VerifiedContent verifyCompletedContent(long userId,
                                                  String taskUuid,
                                                  long expectedSize,
                                                  String expectedSha256) {
        Path completed = verifiedCompletedFile(userId, taskUuid);
        try (FileChannel channel = FileChannel.open(
                completed, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size != expectedSize) {
                throw new BusinessException(HttpStatus.CONFLICT, "FTP 完成文件长度与校验快照不一致");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (hashed != expectedSize || !sha256.equalsIgnoreCase(expectedSha256)) {
                throw new BusinessException(HttpStatus.CONFLICT, "FTP 完成文件在落盘校验期间发生变化");
            }
            return new VerifiedContent(size, sha256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "无法校验 FTP 完成文件：" + ex.getMessage());
        }
    }

    public void deleteCompletedFile(long userId, String taskUuid) {
        try {
            Path completed = verifiedTaskDirectory(userId, taskUuid, false).resolve("completed.bin");
            Files.deleteIfExists(completed);
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "FTP 完成文件清理失败：" + ex.getMessage());
        }
    }

    public void deleteTaskDirectory(long userId, String taskUuid) {
        Path taskDirectory = taskDirectory(userId, taskUuid);
        if (!Files.exists(taskDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path realTaskDirectory = verifiedTaskDirectory(userId, taskUuid, false);
            try (var paths = Files.walk(realTaskDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "FTP 任务文件清理失败：" + ex.getMessage());
        }
    }

    private Path verifiedTaskDirectory(long userId, String taskUuid, boolean create) throws IOException {
        Path expected = taskDirectory(userId, taskUuid);
        Path root = ensureRoot().toRealPath();
        Path current = storageRoot;
        for (Path element : storageRoot.relativize(expected)) {
            current = current.resolve(element);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    throw new IOException("FTP 任务存储目录不存在");
                }
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrent creator is safe only if the checks below still pass.
                }
            }
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 任务存储路径不能包含链接或非目录节点");
            }
            Path expectedCurrent = root.resolve(storageRoot.relativize(current)).normalize();
            if (!current.toRealPath().equals(expectedCurrent)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 任务存储路径越界");
            }
        }
        Path realTaskDirectory = expected.toRealPath();
        Path expectedRealDirectory = root.resolve(storageRoot.relativize(expected)).normalize();
        if (!realTaskDirectory.equals(expectedRealDirectory) || realTaskDirectory.equals(root)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 任务存储路径越界");
        }
        return realTaskDirectory;
    }

    private Path taskDirectory(long userId, String taskUuid) {
        validateIdentifiers(userId, taskUuid);
        Path candidate = storageRoot.resolve("users")
                .resolve(Long.toString(userId))
                .resolve("tasks")
                .resolve(taskUuid)
                .normalize();
        if (!candidate.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 任务存储路径越界");
        }
        return candidate;
    }

    private Path ensureRoot() throws IOException {
        if (!Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(storageRoot);
        }
        if (Files.isSymbolicLink(storageRoot)
                || !Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 存储根目录不能是链接或非目录节点");
        }
        return storageRoot;
    }

    public record OpenPartFile(Path path, FileChannel channel, Object fileKey) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    public record VerifiedContent(long size, String sha256) {
    }

    private static void validateIdentifiers(long userId, String taskUuid) {
        if (userId <= 0 || taskUuid == null) {
            throw new BusinessException("FTP 任务存储标识无效");
        }
        try {
            if (!UUID.fromString(taskUuid).toString().equals(taskUuid)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("FTP 任务存储标识无效");
        }
    }
}
