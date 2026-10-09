package cc.ivera.cookie.infrastructure;

import cc.ivera.shared.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Instagram 登录态真实校验：带 cookie 以导航请求访问首页，
 * 有效 → 200 且页面含 PolarisViewer 登录态；失效 → 302 跳登录或无登录信息。
 * 校验不通过直接抛 BusinessException。
 */
@Slf4j
@Component
public class InstagramCookieVerifier {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    private final String proxy;

    public InstagramCookieVerifier(@Value("${app.proxy:}") String proxy) {
        this.proxy = proxy;
    }

    public void verify(String cookieHeader) {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(15));
            if (proxy != null && !proxy.isBlank()) {
                try {
                    URI pu = URI.create(proxy);
                    builder.proxy(ProxySelector.of(new InetSocketAddress(pu.getHost(), pu.getPort())));
                } catch (Exception e) {
                    log.warn("代理地址无效，将直连：{}", proxy);
                }
            }
            HttpClient client = builder.build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.instagram.com/"))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-us,en;q=0.5")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cookie", cookieHeader)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                throw new BusinessException("Instagram 登录态已失效（被重定向到登录页），请重新登录后导出 cookies.txt");
            }
            if (status != 200) {
                throw new BusinessException("Instagram 校验请求失败（HTTP " + status + "），请稍后重试");
            }
            if (!resp.body().contains("PolarisViewer")) {
                throw new BusinessException("Instagram 登录态已失效（页面无登录信息），请重新登录后导出 cookies.txt");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Instagram 校验请求失败：" + e.getMessage() + "，请检查代理后重试");
        }
    }
}
