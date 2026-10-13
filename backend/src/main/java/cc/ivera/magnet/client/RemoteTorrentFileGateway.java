package cc.ivera.magnet.client;

import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HTTP(S) file bridge: Spring Boot and qBittorrent do NOT share storage.
 * The remote Linux host runs a small authenticated file gateway; bytes are
 * streamed directly from upstream to the servlet response without buffering
 * the whole file in the JVM or browser JavaScript.
 */
@Slf4j
@Component
public class RemoteTorrentFileGateway {

    @Value("${app.magnet.file-gateway-url:}")
    private String gatewayUrl;

    @Value("${app.magnet.file-gateway-secret:}")
    private String gatewaySecret;

    @Value("${app.magnet.file-gateway-allow-http:false}")
    private boolean allowHttp;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Container virtual path (/downloads/uuid/name.iso) => remote gateway relative path. */
    public String relativePath(String configuredSaveRoot, String actualSaveDir, String fileName) {
        String root = normalizeAbsolute(configuredSaveRoot);
        String save = normalizeAbsolute(actualSaveDir);
        boolean windowsDrive = root.matches("(?i)^[a-z]:/.*");
        boolean validParent = windowsDrive
                ? save.regionMatches(true, 0, root + "/", 0, root.length() + 1)
                : save.startsWith(root + "/");
        if (!validParent) {
            throw new BusinessException("种子保存目录不在允许的下载根目录");
        }
        String relative = save.substring(root.length() + 1) + "/" + fileName.replace('\\', '/');
        validateRelative(relative);
        return relative;
    }

    private String normalizeAbsolute(String path) {
        if (path == null || path.isBlank()) throw new BusinessException("未配置 qBittorrent 下载根目录");
        String normalized = path.replace('\\', '/').replaceAll("/+$", "");
        boolean windows = normalized.matches("(?i)^[a-z]:/.+");
        if (!normalized.startsWith("/") && !windows) {
            throw new BusinessException("qBittorrent 保存目录必须是 Windows 或 Linux 绝对路径");
        }
        validateRelative(windows ? normalized.substring(3) : normalized.substring(1));
        return normalized;
    }

    private void validateRelative(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/")
                || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
            throw new BusinessException("非法远程文件路径");
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new BusinessException("非法远程文件路径");
            }
        }
    }

    private URI gatewayUri(String relative) {
        if (gatewayUrl == null || gatewayUrl.isBlank()
                || gatewaySecret == null || gatewaySecret.length() < 32) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 QBT_FILE_GATEWAY_URL / QBT_FILE_GATEWAY_SECRET（密钥需至少 32 字符）");
        }
        URI base;
        try {
            base = URI.create(gatewayUrl);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("远程文件网关地址不合法");
        }
        String scheme = base.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !(allowHttp && "http".equalsIgnoreCase(scheme))) {
            throw new BusinessException("文件网关需使用 HTTPS，或显式设置 QBT_FILE_GATEWAY_ALLOW_HTTP=true 启用 HTTP");
        }
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null) {
            throw new BusinessException("文件网关 URL 配置不合法");
        }
        String normalizedBase = gatewayUrl.replaceAll("/+$", "");
        return URI.create(normalizedBase + "/internal/files?path="
                + URLEncoder.encode(relative, StandardCharsets.UTF_8));
    }

    private String sign(String path, String timestamp, String range) {
        try {
            String input = "GET\n" + path + "\n" + timestamp + "\n" + range;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(gatewaySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("文件网关签名失败", e);
        }
    }

    /** Fail at the authenticated ticket endpoint if no secure remote gateway is configured. */
    public void assertConfigured() {
        gatewayUri("probe");
    }

    /** Prepares upstream before committing downstream headers; supports one HTTP byte range. */
    public void stream(String remoteRelative, String range, String fileName, HttpServletResponse response) {
        validateRelative(remoteRelative);
        if (range != null && (!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")
                || range.length() > 120)) {
            throw new BusinessException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "只支持单段 HTTP Range");
        }
        String requestedRange = range == null ? "" : range;
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest.Builder builder = HttpRequest.newBuilder(gatewayUri(remoteRelative))
                .header("X-Clipfetch-Time", timestamp)
                .header("X-Clipfetch-Signature", sign(remoteRelative, timestamp, requestedRange))
                .header("Accept-Encoding", "identity")
                .GET();
        if (range != null) builder.header("Range", range);

        HttpResponse<InputStream> upstream;
        try {
            upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "连接远程 qBittorrent 文件网关失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "远程文件传输被中断");
        }

        try (InputStream input = upstream.body()) {
            int status = upstream.statusCode();
            if (status == 416) {
                response.setStatus(416);
                upstream.headers().firstValue("Content-Range")
                        .ifPresent(v -> response.setHeader("Content-Range", v));
                response.setHeader("Accept-Ranges", "bytes");
                response.setHeader("Cache-Control", "no-store");
                return;
            }
            if (status != 200 && status != 206) {
                log.warn("Remote file gateway rejected transfer: status={} path={}", status, remoteRelative);
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "远程文件网关返回 HTTP " + status + "，请检查网关权限、文件是否存在");
            }
            if (range != null && status != 206) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "远程文件网关不支持断点续传");
            }
            response.setStatus(status);
            response.setContentType("application/octet-stream");
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            String safeFileName = fileName.replace("\r", "_").replace("\n", "_").replace("\"", "_");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(safeFileName, StandardCharsets.UTF_8).replace("+", "%20"));
            upstream.headers().firstValue("Content-Range")
                    .ifPresent(v -> response.setHeader("Content-Range", v));
            upstream.headers().firstValue("Content-Length")
                    .ifPresent(v -> response.setHeader("Content-Length", v));

            long copied = 0;
            try {
                OutputStream output = response.getOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    copied += read;
                }
                output.flush();
            } catch (IOException e) {
                // Client closed the browser/download connection, or upstream dropped out.
                // Do not throw an exception that attempts to write JSON into octet-stream.
                log.warn("File transfer interrupted after {} bytes ({}): {}", copied, remoteRelative,
                        e.getClass().getSimpleName());
                if (!response.isCommitted()) {
                    throw new BusinessException(HttpStatus.BAD_GATEWAY, "文件传输失败，请重试或使用断点续传");
                }
            }
        } catch (IOException e) {
            // InputStream.close() may fail after the client has gone away.
            log.debug("Closing remote file stream failed: {}", e.toString());
        }
    }
}
