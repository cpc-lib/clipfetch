package cc.ivera.parser.application.parser.video;

import cc.ivera.parser.domain.ParserRuntimeConfig;
import cc.ivera.parser.domain.ParserRuntimeConfigMapper;
import cc.ivera.shared.web.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetMirror TMDB v3 API Key 生命周期管理服务：
 * 1. 数据库持久化（parser_runtime_config，键名 netmirror.tmdb_keys）+ 进程内内存快照；
 * 2. 空库冷启动自动抓取官网首页 -> 前端 JS bundle -> 抗混淆提取 Key -> 验活落库；
 * 3. 失效（401/403）单飞刷新 + 冷却防抖 + 覆盖写库；
 * 4. 面向管理接口提供脱敏状态查询、手动强制刷新与手动覆盖兜底。
 */
@Slf4j
@Service
public class NetMirrorTmdbKeyService {

    public static final String CONFIG_KEY = "netmirror.tmdb_keys";
    public static final String SOURCE_AUTO_FETCH = "AUTO_FETCH";
    public static final String SOURCE_MANUAL = "MANUAL";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String TMDB_VERIFY_URL = "https://tmdb.imdb3.shop/3/configuration?api_key=";
    private static final String TMDB_HOST_ANCHOR = "tmdb.imdb3.shop";
    private static final int MAX_JS_BYTES = 5 * 1024 * 1024;
    private static final int MAX_KEYS_LIMIT = 10;
    private static final int WINDOW_BEFORE = 1500;
    private static final int WINDOW_AFTER = 500;

    private static final Pattern HEX32_FULL = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern HEX32_QUOTED = Pattern.compile("[\"']([0-9a-fA-F]{32})[\"']");
    private static final Pattern HEX32_ARRAY = Pattern.compile(
            "\\[(?:\\s*[\"'][0-9a-fA-F]{32}[\"']\\s*,?)+\\]");
    private static final Pattern HEX32_MULTI_ARRAY = Pattern.compile(
            "\\[(?:\\s*[\"'][0-9a-fA-F]{32}[\"']\\s*,\\s*)+[\"'][0-9a-fA-F]{32}[\"']\\s*\\]");
    private static final Pattern MODULE_SCRIPT_TYPE_FIRST = Pattern.compile(
            "<script\\b[^>]*\\btype=[\"']module[\"'][^>]*\\bsrc=[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE_SCRIPT_SRC_FIRST = Pattern.compile(
            "<script\\b[^>]*\\bsrc=[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"'][^>]*\\btype=[\"']module[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_ASSET_SCRIPT = Pattern.compile(
            "src=[\"'](/assets/index-[^\"']+\\.js(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final ParserRuntimeConfigMapper configMapper;
    private final String siteOrigin;
    private final String siteReferer;
    private final int parseTimeout;
    private final long cooldownSeconds;
    private final HttpClient client;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile KeySnapshot cachedSnapshot;
    private volatile Instant lastAutoRefreshAttempt;

    public NetMirrorTmdbKeyService(
            ParserRuntimeConfigMapper configMapper,
            @Value("${app.netmirror.site-origin:https://netmirror.gold}") String siteOrigin,
            @Value("${app.netmirror.tmdb-refresh-cooldown-seconds:60}") long cooldownSeconds,
            @Value("${app.parse-timeout:60}") int parseTimeout,
            @Value("${app.proxy:}") String proxy) {
        this.configMapper = configMapper;
        this.siteOrigin = normalizeOrigin(siteOrigin);
        this.siteReferer = this.siteOrigin + "/";
        this.cooldownSeconds = Math.max(10L, cooldownSeconds);
        this.parseTimeout = parseTimeout;

        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(parseTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isBlank()) {
            URI u = URI.create(proxy.trim());
            if (u.getHost() != null && u.getPort() > 0) {
                b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort())));
            }
        }
        this.client = b.build();
    }

    public String getSiteOrigin() {
        return siteOrigin;
    }

    public String getSiteReferer() {
        return siteReferer;
    }

    /**
     * 获取当前可用 TMDB Key 列表：优先内存快照，其次数据库，空库时自动从官网抓取提取并落库。
     */
    public List<String> getKeys() {
        KeySnapshot snap = cachedSnapshot;
        if (snap != null && !snap.keys().isEmpty()) {
            return snap.keys();
        }
        KeySnapshot fromDb = loadSnapshotFromDb();
        if (fromDb != null && !fromDb.keys().isEmpty()) {
            cachedSnapshot = fromDb;
            return fromDb.keys();
        }
        return refreshWhenEmpty();
    }

    /**
     * 当既有 Key 全部鉴权失效（401/403）时触发单飞刷新并覆盖数据库。
     */
    public List<String> refreshOnInvalid(List<String> failedKeys) {
        refreshLock.lock();
        try {
            KeySnapshot current = cachedSnapshot;
            if (current != null && !current.keys().isEmpty() && !current.keys().equals(failedKeys)) {
                log.info("[NetMirror] 并发线程已刷新 TMDB Key（{} 个），直接复用新配置", current.keys().size());
                return current.keys();
            }
            checkAutoRefreshCooldown();
            lastAutoRefreshAttempt = Instant.now();
            KeySnapshot updated = fetchVerifyAndPersist();
            return updated.keys();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 只读查询当前数据库中的 TMDB Key 脱敏状态（不触发外网抓取）。
     */
    public TmdbKeyStatus getStatus() {
        KeySnapshot snap = cachedSnapshot;
        if (snap == null) {
            snap = loadSnapshotFromDb();
            if (snap != null) {
                cachedSnapshot = snap;
            }
        }
        if (snap == null || snap.keys().isEmpty()) {
            return new TmdbKeyStatus(false, 0, List.of(), null, null, null, null);
        }
        return toStatus(snap);
    }

    /**
     * 管理接口手动触发强制抓取、验活并覆盖数据库（绕过自动刷新冷却）。
     */
    public TmdbKeyStatus manualRefresh() {
        refreshLock.lock();
        try {
            KeySnapshot updated = fetchVerifyAndPersist();
            return toStatus(updated);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 管理接口手动写入/覆盖自定义 TMDB Key 列表。
     */
    public TmdbKeyStatus saveManualKeys(List<String> rawKeys, boolean verify) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            throw new BusinessException("TMDB Key 列表不能为空");
        }
        Set<String> dedup = new LinkedHashSet<>();
        for (String item : rawKeys) {
            String k = item == null ? "" : item.trim();
            if (!HEX32_FULL.matcher(k).matches()) {
                throw new BusinessException("TMDB Key 格式无效（需为 32 位十六进制字符串）：" + maskKey(k));
            }
            dedup.add(k);
        }
        if (dedup.size() > MAX_KEYS_LIMIT) {
            throw new BusinessException("TMDB Key 数量不能超过 " + MAX_KEYS_LIMIT + " 个");
        }
        List<String> normalized = List.copyOf(dedup);

        if (verify) {
            List<String> invalid = new ArrayList<>();
            int verifiedCount = 0;
            for (String key : normalized) {
                VerifyState state = verifyKey(key);
                if (state == VerifyState.AUTH_INVALID) {
                    invalid.add(maskKey(key));
                } else if (state == VerifyState.VALID) {
                    verifiedCount++;
                }
            }
            if (!invalid.isEmpty()) {
                throw new BusinessException("以下 TMDB Key 校验失效（HTTP 401/403），拒绝覆盖："
                        + String.join(", ", invalid));
            }
            if (verifiedCount == 0) {
                throw new BusinessException("TMDB Key 远端验活未通过（上游不可达或超时），拒绝覆盖");
            }
        }

        refreshLock.lock();
        try {
            KeySnapshot saved = persistKeys(normalized, SOURCE_MANUAL, null, verify);
            log.info("[NetMirror] 已手动覆盖保存 {} 个 TMDB Key（verify={}）", normalized.size(), verify);
            return toStatus(saved);
        } finally {
            refreshLock.unlock();
        }
    }

    public static String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private List<String> refreshWhenEmpty() {
        refreshLock.lock();
        try {
            KeySnapshot current = cachedSnapshot;
            if (current != null && !current.keys().isEmpty()) {
                return current.keys();
            }
            KeySnapshot fromDb = loadSnapshotFromDb();
            if (fromDb != null && !fromDb.keys().isEmpty()) {
                cachedSnapshot = fromDb;
                return fromDb.keys();
            }
            checkAutoRefreshCooldown();
            lastAutoRefreshAttempt = Instant.now();
            KeySnapshot updated = fetchVerifyAndPersist();
            return updated.keys();
        } finally {
            refreshLock.unlock();
        }
    }

    private void checkAutoRefreshCooldown() {
        Instant last = lastAutoRefreshAttempt;
        if (last != null) {
            long elapsed = Duration.between(last, Instant.now()).getSeconds();
            if (elapsed < cooldownSeconds) {
                long remain = cooldownSeconds - elapsed;
                throw new BusinessException("NetMirror TMDB Key 刷新处于冷却期（剩余 " + remain
                        + " 秒），请稍后重试或通过管理接口手动刷新");
            }
        }
    }

    private KeySnapshot fetchVerifyAndPersist() {
        log.info("[NetMirror] 开始从站点 {} 自动抓取 TMDB Key", siteOrigin);
        String homeHtml = fetchHomeHtml();
        URI jsUri = resolveEntryScriptUri(homeHtml);
        String jsBody = fetchJsBundle(jsUri);

        List<String> validKeys = extractAndVerify(jsBody);
        if (validKeys.isEmpty()) {
            throw new BusinessException("从 NetMirror 站点提取有效 TMDB Key 失败，请稍后重试或通过管理接口手动配置");
        }
        KeySnapshot saved = persistKeys(validKeys, SOURCE_AUTO_FETCH, jsUri.toString(), true);
        log.info("[NetMirror] 已从 {} 提取并覆盖保存 {} 个有效 TMDB Key: {}",
                jsUri, validKeys.size(), saved.keys().stream().map(NetMirrorTmdbKeyService::maskKey).toList());
        return saved;
    }

    private String fetchHomeHtml() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(siteReferer))
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException("NetMirror 首页请求失败，HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("NetMirror 首页请求失败：" + safeMsg(e));
        }
    }

    private URI resolveEntryScriptUri(String homeHtml) {
        String scriptSrc = matchFirstGroup(MODULE_SCRIPT_TYPE_FIRST, homeHtml);
        if (scriptSrc == null) {
            scriptSrc = matchFirstGroup(MODULE_SCRIPT_SRC_FIRST, homeHtml);
        }
        if (scriptSrc == null) {
            scriptSrc = matchFirstGroup(INDEX_ASSET_SCRIPT, homeHtml);
        }
        if (scriptSrc == null || scriptSrc.isBlank()) {
            throw new BusinessException("未能从 NetMirror 首页定位前端脚本入口");
        }
        URI baseUri = URI.create(siteReferer);
        URI resolved = baseUri.resolve(scriptSrc.trim());
        String scheme = resolved.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            throw new BusinessException("NetMirror 前端脚本地址协议非法");
        }
        if (resolved.getHost() == null || !resolved.getHost().equalsIgnoreCase(baseUri.getHost())) {
            throw new BusinessException("NetMirror 前端脚本域名与主站不一致，已拒绝抓取");
        }
        return resolved;
    }

    private String fetchJsBundle(URI jsUri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(jsUri)
                    .timeout(Duration.ofSeconds(parseTimeout))
                    .header("User-Agent", UA)
                    .header("Referer", siteReferer)
                    .GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                try (InputStream ignored = resp.body()) {
                    throw new BusinessException("NetMirror 前端脚本下载失败，HTTP " + resp.statusCode());
                }
            }
            try (InputStream in = resp.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_JS_BYTES) {
                        throw new BusinessException("NetMirror 前端脚本体积超过 5MB 上限，已中止读取");
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("NetMirror 前端脚本下载失败：" + safeMsg(e));
        }
    }

    private List<String> extractAndVerify(String jsBody) {
        List<String> primaryCandidates = extractCandidatesNearAnchor(jsBody);
        if (!primaryCandidates.isEmpty()) {
            List<String> valid = filterValidKeys(primaryCandidates);
            if (!valid.isEmpty()) {
                return valid;
            }
            log.warn("[NetMirror] 锚点附近提取到 {} 个候选 Key 但验活均未通过，尝试全文兜底扫描",
                    primaryCandidates.size());
        }
        List<String> fallbackCandidates = extractFallbackCandidates(jsBody, primaryCandidates);
        if (fallbackCandidates.isEmpty()) {
            return List.of();
        }
        return filterValidKeys(fallbackCandidates);
    }

    private List<String> extractCandidatesNearAnchor(String jsBody) {
        Set<String> found = new LinkedHashSet<>();
        int idx = 0;
        while ((idx = jsBody.indexOf(TMDB_HOST_ANCHOR, idx)) >= 0) {
            int start = Math.max(0, idx - WINDOW_BEFORE);
            int end = Math.min(jsBody.length(), idx + WINDOW_AFTER);
            String window = jsBody.substring(start, end);
            Matcher arrMatcher = HEX32_ARRAY.matcher(window);
            while (arrMatcher.find()) {
                collectHex32(arrMatcher.group(), found);
            }
            idx += TMDB_HOST_ANCHOR.length();
        }
        return new ArrayList<>(found);
    }

    private List<String> extractFallbackCandidates(String jsBody, List<String> alreadyTried) {
        Set<String> tried = new LinkedHashSet<>(alreadyTried);
        Set<String> found = new LinkedHashSet<>();
        Matcher arrMatcher = HEX32_MULTI_ARRAY.matcher(jsBody);
        while (arrMatcher.find() && found.size() < MAX_KEYS_LIMIT) {
            Matcher km = HEX32_QUOTED.matcher(arrMatcher.group());
            while (km.find() && found.size() < MAX_KEYS_LIMIT) {
                String k = km.group(1);
                if (!tried.contains(k)) {
                    found.add(k);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static void collectHex32(String arrayLiteral, Set<String> target) {
        Matcher km = HEX32_QUOTED.matcher(arrayLiteral);
        while (km.find() && target.size() < MAX_KEYS_LIMIT) {
            target.add(km.group(1));
        }
    }

    private List<String> filterValidKeys(List<String> candidates) {
        List<String> valid = new ArrayList<>();
        for (String key : candidates) {
            VerifyState state = verifyKey(key);
            if (state == VerifyState.VALID) {
                valid.add(key);
            } else {
                log.warn("[NetMirror] 候选 TMDB Key {} 验活未通过: {}", maskKey(key), state);
            }
        }
        return valid;
    }

    private VerifyState verifyKey(String key) {
        int verifyTimeout = Math.max(5, Math.min(parseTimeout, 15));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(TMDB_VERIFY_URL + key))
                    .timeout(Duration.ofSeconds(verifyTimeout))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code == 200 || code == 429) {
                return VerifyState.VALID;
            }
            if (code == 401 || code == 403) {
                return VerifyState.AUTH_INVALID;
            }
            log.warn("[NetMirror] TMDB Key {} 验活返回异常状态 HTTP {}", maskKey(key), code);
            return VerifyState.UNREACHABLE;
        } catch (Exception e) {
            log.warn("[NetMirror] TMDB Key {} 验活请求异常: {}", maskKey(key), safeMsg(e));
            return VerifyState.UNREACHABLE;
        }
    }

    private KeySnapshot loadSnapshotFromDb() {
        ParserRuntimeConfig row = configMapper.findByKey(CONFIG_KEY);
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return null;
        }
        try {
            List<String> raw = MAPPER.readValue(row.getConfigValue(), STRING_LIST_TYPE);
            Set<String> valid = new LinkedHashSet<>();
            if (raw != null) {
                for (String k : raw) {
                    if (k != null && HEX32_FULL.matcher(k.trim()).matches()) {
                        valid.add(k.trim());
                    }
                }
            }
            if (valid.isEmpty()) {
                return null;
            }
            return new KeySnapshot(
                    List.copyOf(valid),
                    row.getSource(),
                    row.getSourceUrl(),
                    row.getLastVerifiedAt(),
                    row.getLastRefreshedAt());
        } catch (Exception e) {
            log.warn("[NetMirror] 解析数据库 TMDB Key JSON 失败，将重新获取: {}", safeMsg(e));
            return null;
        }
    }

    private KeySnapshot persistKeys(List<String> keys, String source, String sourceUrl, boolean verified) {
        String json;
        try {
            json = MAPPER.writeValueAsString(keys);
        } catch (Exception e) {
            throw new BusinessException("序列化 TMDB Key 失败：" + safeMsg(e));
        }
        LocalDateTime now = LocalDateTime.now();
        ParserRuntimeConfig existing = configMapper.findByKey(CONFIG_KEY);
        LocalDateTime verifiedAt;
        if (existing == null) {
            verifiedAt = verified ? now : null;
            ParserRuntimeConfig entity = ParserRuntimeConfig.builder()
                    .configKey(CONFIG_KEY)
                    .configValue(json)
                    .source(source)
                    .sourceUrl(sourceUrl)
                    .lastVerifiedAt(verifiedAt)
                    .lastRefreshedAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .deleted(0L)
                    .build();
            configMapper.insertConfig(entity);
        } else {
            verifiedAt = verified ? now : existing.getLastVerifiedAt();
            existing.setConfigValue(json);
            existing.setSource(source);
            existing.setSourceUrl(sourceUrl);
            existing.setLastVerifiedAt(verifiedAt);
            existing.setLastRefreshedAt(now);
            existing.setUpdatedAt(now);
            configMapper.updateConfig(existing);
        }
        KeySnapshot snap = new KeySnapshot(List.copyOf(keys), source, sourceUrl, verifiedAt, now);
        cachedSnapshot = snap;
        return snap;
    }

    private static TmdbKeyStatus toStatus(KeySnapshot snap) {
        List<String> masked = snap.keys().stream().map(NetMirrorTmdbKeyService::maskKey).toList();
        return new TmdbKeyStatus(
                true,
                snap.keys().size(),
                masked,
                snap.source(),
                snap.sourceUrl(),
                snap.lastVerifiedAt(),
                snap.lastRefreshedAt());
    }

    private static String normalizeOrigin(String raw) {
        String s = (raw == null || raw.isBlank()) ? "https://netmirror.gold" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "https://netmirror.gold" : s;
    }

    private static String matchFirstGroup(Pattern pattern, String input) {
        if (input == null) {
            return null;
        }
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    private enum VerifyState {
        VALID, AUTH_INVALID, UNREACHABLE
    }

    private record KeySnapshot(
            List<String> keys,
            String source,
            String sourceUrl,
            LocalDateTime lastVerifiedAt,
            LocalDateTime lastRefreshedAt
    ) {}

    public record TmdbKeyStatus(
            boolean configured,
            int count,
            List<String> maskedKeys,
            String source,
            String sourceUrl,
            LocalDateTime lastVerifiedAt,
            LocalDateTime lastRefreshedAt
    ) {}
}
