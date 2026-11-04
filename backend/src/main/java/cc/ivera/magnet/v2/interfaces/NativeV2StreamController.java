package cc.ivera.magnet.v2.interfaces;

import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.application.port.NativeV2TaskFileRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskRepository;
import cc.ivera.magnet.v2.application.ticket.NativeV2TicketStore;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import cc.ivera.magnet.v2.domain.task.TaskStatus;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 浏览器原生下载：不在登录拦截器内，凭证是绑定用户/任务/文件的短时 ticket。
 * 每次请求重新校验归属与完成状态，并在每个请求内重新解析安全真实路径。
 */
@RestController
@RequestMapping("/api/magnet/v2")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.magnet.native-v2.enabled", havingValue = "true")
public class NativeV2StreamController {

    private static final int BUFFER_BYTES = 8 * 1024;

    private final NativeV2TicketStore tickets;
    private final NativeV2TaskRepository tasks;
    private final NativeV2TaskFileRepository taskFiles;
    private final NativeV2ResourceRepository resources;
    private final NativeV2Storage storage;
    private final Clock clock;

    @GetMapping("/downloads/stream/{ticket}")
    public void stream(@PathVariable String ticket,
                       @RequestHeader(value = "Range", required = false) String range,
                       HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Referrer-Policy", "no-referrer");

        NativeV2TicketStore.Ticket capability = tickets.find(ticket, clock.instant())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "下载凭证无效或已过期"));
        MagnetV2DownloadTask task = tasks.findOwned(capability.taskUuid(), capability.userId())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "下载任务不存在"));
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new V2DomainException(ErrorCodes.CONFLICT, "任务未完成，不能下载");
        }
        MagnetV2DownloadTaskFile taskFile = taskFiles.findByTask(task.getId()).stream()
                .filter(file -> file.getFileIndex() == capability.fileIndex())
                .findFirst()
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "文件不存在"));
        if (!Boolean.TRUE.equals(taskFile.getSelected()) || !"COMPLETE".equals(taskFile.getState())) {
            throw new V2DomainException(ErrorCodes.CONFLICT, "文件未完成，不能下载");
        }
        MagnetV2Resource resource = resources.findById(task.getResourceId())
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "资源不存在"));
        String relativePath = resources.findFiles(resource.getId()).stream()
                .filter(file -> file.getFileIndex() == capability.fileIndex())
                .map(MagnetV2ResourceFile::getRelativePath)
                .findFirst()
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "文件不存在"));
        Path path = storage.resolveCompletedFile(UUID.fromString(resource.getResourceUuid()), relativePath);

        long size = Files.size(path);
        long[] selected = range == null || range.isBlank() ? new long[]{0, size - 1} : parseRange(range, size);
        if (selected == null) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        long start = selected[0];
        long length = selected[1] - start + 1;

        boolean partial = range != null && !range.isBlank();
        response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(length);
        response.setHeader("Content-Disposition", contentDisposition(fileName(relativePath)));
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + selected[1] + "/" + size);
        }
        writeRange(path, start, length, response.getOutputStream());
    }

    private static void writeRange(Path path, long start, long length, OutputStream out) throws IOException {
        if (length <= 0) {
            out.flush();
            return;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(start);
            byte[] buffer = new byte[BUFFER_BYTES];
            ByteBuffer target = ByteBuffer.wrap(buffer);
            long remaining = length;
            while (remaining > 0) {
                target.clear();
                target.limit((int) Math.min(BUFFER_BYTES, remaining));
                int read = channel.read(target);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /** @return {start, endInclusive} or null when the header is invalid/unsatisfiable */
    private static long[] parseRange(String header, long size) {
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6) || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring(6).trim();
        long start;
        long end;
        try {
            if (spec.startsWith("-")) {
                long suffix = Long.parseLong(spec.substring(1).trim());
                if (suffix <= 0 || size == 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                int dash = spec.indexOf('-');
                if (dash < 0) {
                    return null;
                }
                start = Long.parseLong(spec.substring(0, dash).trim());
                String endText = spec.substring(dash + 1).trim();
                if (start < 0 || start >= size) {
                    return null;
                }
                end = endText.isEmpty() ? size - 1 : Math.min(Long.parseLong(endText), size - 1);
            }
        } catch (NumberFormatException error) {
            return null;
        }
        if (size == 0 || start > end || end < 0) {
            return null;
        }
        return new long[]{start, end};
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    private static String contentDisposition(String name) {
        String fallback = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
