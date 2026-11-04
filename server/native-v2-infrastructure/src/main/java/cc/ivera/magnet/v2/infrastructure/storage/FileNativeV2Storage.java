package cc.ivera.magnet.v2.infrastructure.storage;

import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.torrent.PathSanitizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class FileNativeV2Storage implements NativeV2Storage {

    private final Path root;
    private final Path realRoot;

    public FileNativeV2Storage(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
            this.realRoot = this.root.toRealPath();
        } catch (IOException | RuntimeException error) {
            throw ioError("Native v2 存储根目录不可用，请检查目录权限", error);
        }
    }

    @Override
    public Path parseInput(UUID parseId) {
        return root.resolve("parse-input").resolve(parseId + ".torrent");
    }

    @Override
    public Path metadata(UUID resourceId) {
        return root.resolve("metadata").resolve(resourceId + ".torrent");
    }

    @Override
    public Path resourceRoot(UUID resourceId) {
        return root.resolve("resources").resolve(resourceId.toString());
    }

    @Override
    public Path resume(UUID resourceId) {
        return root.resolve("resume").resolve(resourceId + ".dat");
    }

    @Override
    public void writeParseInput(UUID parseId, byte[] bytes) {
        writeAtomically(parseInput(parseId), bytes);
    }

    @Override
    public byte[] readParseInput(UUID parseId) {
        Path target = parseInput(parseId);
        try {
            Path realTarget = target.toRealPath();
            if (!realTarget.equals(expectedRealPath(target)) || !Files.isRegularFile(realTarget)) {
                throw invalidPath("解析输入路径越过 Native v2 存储根目录");
            }
            return Files.readAllBytes(realTarget);
        } catch (NoSuchFileException error) {
            throw new V2DomainException(ErrorCodes.INPUT_LOST, "解析输入已丢失，请重新提交");
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("读取 Native v2 解析输入失败，请检查存储目录", error);
        }
    }

    @Override
    public void deleteParseInput(UUID parseId) {
        Path target = parseInput(parseId);
        try {
            if (!Files.exists(target)) {
                return;
            }
            Path realTarget = target.toRealPath();
            if (!realTarget.equals(expectedRealPath(target)) || !Files.isRegularFile(realTarget)) {
                throw invalidPath("解析输入路径越过 Native v2 存储根目录");
            }
            Files.delete(realTarget);
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("删除 Native v2 解析输入失败，请检查存储目录", error);
        }
    }

    @Override
    public void writeMetadata(UUID resourceId, byte[] bytes) {
        writeAtomically(metadata(resourceId), bytes);
    }

    @Override
    public byte[] readMetadata(UUID resourceId) {
        Path target = metadata(resourceId);
        try {
            Path realTarget = target.toRealPath();
            if (!realTarget.equals(expectedRealPath(target)) || !Files.isRegularFile(realTarget)) {
                throw invalidPath("metadata 路径越过 Native v2 存储根目录");
            }
            return Files.readAllBytes(realTarget);
        } catch (NoSuchFileException error) {
            throw new V2DomainException(ErrorCodes.INPUT_LOST, "metadata 已丢失，请重新解析该链接后再创建下载任务");
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("读取 Native v2 metadata 失败，请检查存储目录", error);
        }
    }

    @Override
    public void writeResume(UUID resourceId, byte[] bytes) {
        writeAtomically(resume(resourceId), bytes);
    }

    @Override
    public Optional<byte[]> readResume(UUID resourceId) {
        Path target = resume(resourceId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            Path realTarget = target.toRealPath();
            if (!realTarget.equals(expectedRealPath(target)) || !Files.isRegularFile(realTarget)) {
                throw invalidPath("resume 文件路径越过 Native v2 存储根目录");
            }
            return Optional.of(Files.readAllBytes(realTarget));
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("读取 Native v2 resume 失败，请检查存储目录", error);
        }
    }

    private void writeAtomically(Path target, byte[] bytes) {
        if (target == null || bytes == null) {
            throw invalidPath("写入目标和内容不能为空");
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root) || normalized.getParent() == null) {
            throw invalidPath("写入目标必须位于 Native v2 存储根目录内");
        }

        Path temporary = null;
        try {
            Path parent = normalized.getParent();
            verifyExistingAncestor(parent);
            Files.createDirectories(parent);
            verifyRealParent(parent);
            temporary = Files.createTempFile(parent, normalized.getFileName() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            verifyRealParent(parent);
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("原子写入 Native v2 文件失败，请检查磁盘空间和目录权限", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original write error is the actionable failure.
                }
            }
        }
    }

    @Override
    public Path resolveCompletedFile(UUID resourceId, String safeRelativePath) {
        String canonical = canonicalRelativePath(safeRelativePath);
        Path resource = resourceRoot(resourceId).toAbsolutePath().normalize();
        try {
            Path realResource = resource.toRealPath();
            if (!realResource.equals(expectedRealPath(resource))) {
                throw invalidPath("资源目录越过 Native v2 存储根目录");
            }
            Path candidate = resource.resolve(canonical).normalize();
            if (!candidate.startsWith(resource)) {
                throw invalidPath("文件路径越过资源目录");
            }
            Path realCandidate = candidate.toRealPath();
            Path expectedCandidate = realResource.resolve(resource.relativize(candidate)).normalize();
            if (!realCandidate.equals(expectedCandidate)) {
                throw invalidPath("文件真实路径越过资源目录");
            }
            if (!Files.isRegularFile(realCandidate)) {
                throw notFound();
            }
            return realCandidate;
        } catch (NoSuchFileException error) {
            throw notFound();
        } catch (V2DomainException error) {
            throw error;
        } catch (IOException error) {
            throw ioError("读取已完成文件失败，请检查存储目录", error);
        }
    }

    private String canonicalRelativePath(String value) {
        if (value == null || value.isEmpty()) {
            throw invalidPath("文件相对路径不能为空");
        }
        String canonical = PathSanitizer.joinRelativePath(Arrays.asList(value.split("/", -1)));
        if (!canonical.equals(value)) {
            throw invalidPath("文件路径必须使用规范的斜杠相对路径");
        }
        return canonical;
    }

    private void verifyExistingAncestor(Path parent) throws IOException {
        Path existing = parent;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().equals(expectedRealPath(existing))) {
            throw invalidPath("写入目录的真实路径越过 Native v2 存储根目录");
        }
    }

    private void verifyRealParent(Path parent) throws IOException {
        if (!parent.toRealPath().equals(expectedRealPath(parent))) {
            throw invalidPath("写入目录的真实路径越过 Native v2 存储根目录");
        }
    }

    private Path expectedRealPath(Path logicalPath) {
        Path normalized = logicalPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw invalidPath("路径必须位于 Native v2 存储根目录内");
        }
        return realRoot.resolve(root.relativize(normalized)).normalize();
    }

    private static V2DomainException invalidPath(String message) {
        return new V2DomainException(ErrorCodes.INVALID_PATH, message);
    }

    private static V2DomainException notFound() {
        return new V2DomainException(ErrorCodes.NOT_FOUND, "文件不存在或尚未完成，请刷新任务状态后重试");
    }

    private static V2DomainException ioError(String message, Throwable cause) {
        V2DomainException error = new V2DomainException(ErrorCodes.STORAGE_UNAVAILABLE, message);
        error.initCause(cause);
        return error;
    }
}
