package com.fvd.video.interfaces;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MediaController {

    private static final HttpClient WALLPAPER_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String WALLPAPER_FALLBACK =
            "https://www.bing.com/th?id=OHR.ChattoogaRiver_ZH-CN9453791496_1920x1080.jpg";
    private static final com.fasterxml.jackson.databind.ObjectMapper WALLPAPER_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 随机 Bing 每日壁纸：取近 8 天中随机一天的主图并 302 跳转，前端封面兜底用。
     */
    @GetMapping("/wallpaper")
    public void wallpaper(HttpServletResponse response) throws java.io.IOException {
        String location = WALLPAPER_FALLBACK;
        try {
            int idx = ThreadLocalRandom.current().nextInt(8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=" + idx + "&n=1&mkt=zh-CN"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> res = WALLPAPER_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String url = WALLPAPER_MAPPER.readTree(res.body()).path("images").path(0).path("url").asText("");
            if (!url.isBlank()) {
                location = "https://www.bing.com" + url;
            }
        } catch (Exception e) {
            log.debug("随机壁纸获取失败，使用兜底图: {}", e.getMessage());
        }
        response.sendRedirect(location);
    }

    /**
     * 图片代理：sinaimg 等 CDN 校验 Referer，浏览器直链（no-referrer）会 403。
     * 服务端带上平台 Referer 抓取后流式回写，供前端 <img> 安全加载。
     */
    @GetMapping("/image-proxy")
    public void imageProxy(@RequestParam("url") String url, HttpServletResponse response) throws java.io.IOException {
        if (url == null || url.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String referer;
        try {
            String host = URI.create(url).getHost();
            if (host != null && (host.endsWith("sinaimg.cn") || host.endsWith("weibo.com")
                    || host.endsWith("weibo.cn") || host.endsWith("weibocdn.com"))) {
                referer = "https://weibo.com/";
            } else {
                referer = "";
            }
        } catch (Exception e) {
            referer = "";
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                    .header("Accept", "image/*,video/*,*/*;q=0.8")
                    .GET();
            if (!referer.isEmpty()) {
                rb.header("Referer", referer);
            }
            HttpResponse<java.io.InputStream> resp =
                    client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                response.setStatus(resp.statusCode());
                return;
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            response.setContentType(ct);
            response.setHeader("Cache-Control", "public, max-age=3600");
            try (var in = resp.body(); var out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (Exception e) {
            log.debug("图片代理失败 {}: {}", url, e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        }
    }
}
