package cc.ivera.ftp.application;

import cc.ivera.ftp.domain.FtpDownloadTask;
import cc.ivera.ftp.domain.FtpDownloadTaskMapper;
import cc.ivera.ftp.domain.FtpTaskState;
import cc.ivera.ftp.infrastructure.FtpPathPolicy;
import cc.ivera.ftp.infrastructure.FtpProperties;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FtpFileService {

    private final FtpProperties properties;
    private final FtpDownloadTaskMapper taskMapper;
    private final FtpPathPolicy pathPolicy;

    public record ByteRange(long start, long end, long totalSize, boolean partial) {
        public long contentLength() {
            return totalSize == 0 ? 0 : end - start + 1;
        }
    }

    public void stream(long userId,
                       String taskUuid,
                       int fileIndex,
                       String rangeHeader,
                       HttpServletResponse response) {
        if (fileIndex != 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FTP 单文件任务仅支持文件索引 0");
        }
        FtpDownloadTask task = taskMapper.findOwnedByUuid(taskUuid, userId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FTP 下载任务不存在");
        }
        if (!FtpTaskState.COMPLETED.name().equals(task.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "FTP 下载任务尚未完成，暂不可取回文件");
        }
        Path file = pathPolicy.verifiedCompletedFile(userId, taskUuid);
        int bufferSize = Math.max(4096, properties.getFileBufferBytes());
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (task.getTotalBytes() != null && task.getTotalBytes() != size) {
                throw new BusinessException(HttpStatus.CONFLICT, "FTP 已完成文件长度校验失败");
            }
            ByteRange range;
            try {
                range = parseSingleRange(rangeHeader, size);
            } catch (IllegalArgumentException ex) {
                writeRangeError(response, size, ex.getMessage());
                return;
            }
            response.setStatus(range.partial() ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
            response.setHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(task.getDisplayName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(range.contentLength()));
            if (range.partial()) {
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + size);
            }
            if (size == 0) {
                return;
            }
            channel.position(range.start());
            long remaining = range.contentLength();
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            try (OutputStream output = response.getOutputStream()) {
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer);
                    if (read < 0) {
                        throw new IOException("文件提前结束");
                    }
                    output.write(buffer.array(), 0, read);
                    remaining -= read;
                }
                output.flush();
            }
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 FTP 已完成文件失败");
        }
    }

    public static ByteRange parseSingleRange(String rangeHeader, long totalSize) {
        if (totalSize < 0) {
            throw new IllegalArgumentException("文件大小非法");
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new ByteRange(0, Math.max(0, totalSize - 1), totalSize, false);
        }
        if (totalSize == 0) {
            throw new IllegalArgumentException("空文件不支持 Range 请求");
        }
        String trimmed = rangeHeader.trim();
        if (!trimmed.startsWith("bytes=")) {
            throw new IllegalArgumentException("仅支持 bytes 范围请求");
        }
        String spec = trimmed.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            throw new IllegalArgumentException("仅支持单段 Range 请求");
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("Range 格式非法");
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    throw new IllegalArgumentException("Range 尾部长度必须大于 0");
                }
                return new ByteRange(Math.max(0, totalSize - suffix), totalSize - 1, totalSize, true);
            }
            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? totalSize - 1 : Long.parseLong(endPart);
            if (start < 0 || start >= totalSize || end < start) {
                throw new IllegalArgumentException("Range 超出文件字节范围");
            }
            return new ByteRange(start, Math.min(totalSize - 1, end), totalSize, true);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Range 数值格式非法");
        }
    }

    private static void writeRangeError(HttpServletResponse response, long size, String message) {
        response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"success\":false,\"error\":\"" + message + "\"}");
            response.getWriter().flush();
        } catch (IOException ignored) {
        }
    }
}
