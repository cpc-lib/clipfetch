package cc.ivera.ed2k.application;

import cc.ivera.ed2k.domain.Ed2kDownloadTask;
import cc.ivera.ed2k.domain.Ed2kDownloadTaskMapper;
import cc.ivera.ed2k.domain.Ed2kResource;
import cc.ivera.ed2k.domain.Ed2kResourceMapper;
import cc.ivera.ed2k.domain.Ed2kResourceState;
import cc.ivera.ed2k.domain.Ed2kTaskState;
import cc.ivera.ed2k.infrastructure.AmuleProperties;
import cc.ivera.ed2k.infrastructure.FilePathPolicy;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
@RequiredArgsConstructor
public class Ed2kFileService {

    private final AmuleProperties properties;
    private final Ed2kDownloadTaskMapper taskMapper;
    private final Ed2kResourceMapper resourceMapper;

    public record ByteRange(long start, long end, long totalSize, boolean partial) {
        public long contentLength() {
            return end - start + 1;
        }
    }

    public void streamTaskFile(long userId,
                               String taskUuid,
                               int fileIndex,
                               String rangeHeader,
                               HttpServletResponse response) {
        if (fileIndex != 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "eD2k 单文件任务仅支持文件索引 0");
        }
        Ed2kDownloadTask task = taskMapper.findOwnedByUuid(taskUuid, userId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "下载任务不存在");
        }
        if (!Ed2kTaskState.COMPLETED.name().equals(task.getTaskState())) {
            throw new BusinessException(HttpStatus.CONFLICT, "下载任务尚未完成，暂不可取回文件");
        }

        Ed2kResource resource = resourceMapper.findById(task.getResourceId());
        if (resource == null
                || !Ed2kResourceState.COMPLETED.name().equals(resource.getEngineState())
                || resource.getCompletedPath() == null
                || resource.getCompletedPath().isBlank()) {
            throw new BusinessException(HttpStatus.CONFLICT, "共享资源文件尚未完成核验");
        }

        Path verifiedFile = FilePathPolicy.resolveVerifiedCompletedFile(
                Path.of(properties.getIncomingRoot()),
                resource.getCompletedPath(),
                resource.getFileSize()
        );

        long totalSize = resource.getFileSize();
        ByteRange range;
        try {
            range = parseSingleRange(rangeHeader, totalSize);
        } catch (IllegalArgumentException ex) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"success\":false,\"error\":\"" + ex.getMessage() + "\"}");
                response.getWriter().flush();
            } catch (IOException ignored) {
            }
            return;
        }

        response.setStatus(range.partial() ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                FilePathPolicy.buildContentDispositionAttachment(task.getRequestedName()));
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(range.contentLength()));
        if (range.partial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + totalSize);
        }

        int bufferSize = Math.max(4096, properties.getFileBufferBytes());
        try (SeekableByteChannel channel = Files.newByteChannel(verifiedFile, StandardOpenOption.READ);
             OutputStream out = response.getOutputStream()) {
            channel.position(range.start());
            long remaining = range.contentLength();
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            byte[] transfer = new byte[bufferSize];
            while (remaining > 0) {
                buffer.clear();
                if (remaining < bufferSize) {
                    buffer.limit((int) remaining);
                }
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                buffer.flip();
                buffer.get(transfer, 0, read);
                out.write(transfer, 0, read);
                remaining -= read;
            }
            out.flush();
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "读取已完成文件流失败：" + ex.getMessage());
        }
    }

    public static ByteRange parseSingleRange(String rangeHeader, long totalSize) {
        if (totalSize <= 0) {
            throw new IllegalArgumentException("文件大小非法");
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new ByteRange(0, totalSize - 1, totalSize, false);
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
                // suffix-byte-range-spec: bytes=-500
                if (endPart.isEmpty()) {
                    throw new IllegalArgumentException("Range 起止位置均为空");
                }
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    throw new IllegalArgumentException("Range 尾部长度必须大于 0");
                }
                long start = Math.max(0L, totalSize - suffixLength);
                return new ByteRange(start, totalSize - 1, totalSize, true);
            }

            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? (totalSize - 1) : Long.parseLong(endPart);
            if (start < 0 || start >= totalSize || end < start) {
                throw new IllegalArgumentException("Range 超出文件字节范围");
            }
            long clampedEnd = Math.min(totalSize - 1, end);
            return new ByteRange(start, clampedEnd, totalSize, true);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Range 数值格式非法");
        }
    }
}
