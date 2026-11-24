package cc.ivera.parser.infrastructure.playback;

import cc.ivera.parser.application.PlaybackTicket;
import cc.ivera.parser.infrastructure.service.YtDlpService;
import cc.ivera.shared.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK HttpClient 流式转发：带票据记录的 Referer/Cookie/UA，处理 Range 与重定向逐跳校验。
 * 不缓存完整媒体字节。
 */
@Slf4j
@Component
public class PlaybackProxyClient {

    private static final int MAX_PLAYLIST_BYTES = 5 * 1024 * 1024;

    private final UpstreamTargetPolicy policy;
    private final HttpClient directClient;
    private final HttpClient proxyClient;
    private final int maxRedirects;
    private final long timeoutSeconds;

    public PlaybackProxyClient(UpstreamTargetPolicy policy,
                               YtDlpService ytDlp,
                               @Value("${app.play.upstream-connect-timeout-ms:10000}") int connectTimeoutMs,
                               @Value("${app.play.upstream-timeout-seconds:3600}") long timeoutSeconds,
                               @Value("${app.play.max-redirects:5}") int maxRedirects) {
        this.policy = policy;
        this.maxRedirects = maxRedirects;
        this.timeoutSeconds = timeoutSeconds;
        this.directClient = buildClient(connectTimeoutMs, null);
        this.proxyClient = buildClient(connectTimeoutMs, ytDlp.proxy());
    }

    private HttpClient buildClient(int connectTimeoutMs, String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy);
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
            } catch (Exception e) {
                log.warn("播放出口代理配置无效，回退直连: {}", proxy);
            }
        }
        return builder.build();
    }

    /**
     * 打开上游响应（跟随合法重定向）。调用方必须关闭返回对象。
     */
    public Upstream open(PlaybackTicket ticket, URI uri, String range) {
        URI current = uri;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            policy.validate(current);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", ticket.getUserAgent())
                    .header("Accept", "*/*")
                    .GET();
            if (ticket.getReferer() != null && !ticket.getReferer().isBlank()) {
                builder.header("Referer", ticket.getReferer());
            }
            if (ticket.getCookie() != null && !ticket.getCookie().isBlank()
                    && ticket.getCookie().indexOf('\n') < 0 && ticket.getCookie().indexOf('\r') < 0) {
                builder.header("Cookie", ticket.getCookie());
            }
            if (range != null && !range.isBlank()) {
                builder.header("Range", range);
            }
            try {
                HttpClient client = ticket.isUseProxy() ? proxyClient : directClient;
                HttpResponse<InputStream> resp = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                if (status >= 300 && status < 400) {
                    String location = resp.headers().firstValue("Location").orElse(null);
                    resp.body().close();
                    if (location == null || location.isBlank()) {
                        throw new BusinessException("播放地址重定向缺少目标地址");
                    }
                    current = current.resolve(location);
                    continue;
                }
                return new Upstream(status, resp.headers(), resp.body(), current);
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException("获取播放流失败：" + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("获取播放流被中断");
            }
        }
        throw new BusinessException("播放地址重定向次数过多");
    }

    /**
     * 拉取 playlist 文本：非 2xx 或 HTML 防盗链页面直接拒绝。
     */
    public PlaylistText fetchPlaylist(PlaybackTicket ticket, URI uri) {
        try (Upstream up = open(ticket, uri, null)) {
            if (up.status() < 200 || up.status() >= 300) {
                throw new BusinessException("播放列表获取失败（上游状态码 " + up.status() + "）");
            }
            String contentType = up.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase().contains("text/html")) {
                throw new BusinessException("播放链接已失效，请重新播放");
            }
            byte[] body = readLimited(up.body());
            String text = new String(body, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return new PlaylistText(text, up.finalUri(), contentType);
        } catch (IOException e) {
            throw new BusinessException("读取播放列表失败：" + e.getMessage());
        }
    }

    private byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_PLAYLIST_BYTES) {
                throw new BusinessException("播放列表过大，已拒绝处理");
            }
        }
        return out.toByteArray();
    }

    public record Upstream(int status, HttpHeaders headers, InputStream body, URI finalUri) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    public record PlaylistText(String text, URI finalUri, String contentType) {
    }
}
