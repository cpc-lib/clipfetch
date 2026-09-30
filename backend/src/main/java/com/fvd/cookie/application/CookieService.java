package com.fvd.cookie.application;

import com.fvd.auth.domain.User;
import com.fvd.cookie.domain.UserCookie;
import com.fvd.cookie.domain.UserCookieMapper;
import com.fvd.cookie.infrastructure.InstagramCookieVerifier;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户平台 cookies 维护：上传校验、状态查询、失效标记、yt-dlp 临时文件落地。
 * 支持 YouTube/抖音/Twitter/TikTok/Instagram/Bilibili/央视网；抖音/Instagram 必须登录配置，
 * 其余平台可选（用于会员或限流内容）。
 */
@Slf4j
@Service
public class CookieService {

    public static final Set<Platform> SUPPORTED = Set.of(
            Platform.YOUTUBE, Platform.DOUYIN, Platform.TWITTER,
            Platform.TIKTOK, Platform.INSTAGRAM, Platform.BILIBILI, Platform.CCTV);

    /**
     * 各平台登录态的关键 cookie 名（上传时校验存在性）
     */
    private static final Map<Platform, String> REQUIRED_COOKIE = Map.of(
            Platform.DOUYIN, "sessionid",
            Platform.INSTAGRAM, "sessionid",
            Platform.TIKTOK, "sessionid",
            Platform.TWITTER, "auth_token",
            Platform.BILIBILI, "SESSDATA",
            Platform.YOUTUBE, "SID");

    private final UserCookieMapper userCookieMapper;
    private final InstagramCookieVerifier instagramVerifier;

    public CookieService(UserCookieMapper userCookieMapper,
                         InstagramCookieVerifier instagramVerifier) {
        this.userCookieMapper = userCookieMapper;
        this.instagramVerifier = instagramVerifier;
    }

    // ===== 对外状态视图 =====

    /**
     * 必须配置 cookies 才能使用的平台
     */
    public static boolean isRequired(Platform p) {
        return p == Platform.DOUYIN || p == Platform.INSTAGRAM;
    }

    public static List<ParsedCookie> parseNetscape(String content) {
        List<ParsedCookie> cookies = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // domain  includeSubdomains  path  secure  expires  name  value
            String[] f = line.split("\t", 7);
            if (f.length < 7 || f[5].isBlank()) {
                continue;
            }
            cookies.add(new ParsedCookie(f[0], f[5], f[6]));
        }
        return cookies;
    }

    /**
     * 拼成经典 Netscape 风格 Cookie 请求头（JDK CookieManager 的 RFC2965 头会被平台 WAF 拒绝）
     */
    public static String toCookieHeader(String content, Platform p) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ParsedCookie c : parseNetscape(content)) {
            if (domainMatches(c.domain(), p)) {
                parts.add(c.name() + "=" + c.value());
            }
        }
        return String.join("; ", parts);
    }

    // ===== 上传 / 删除 =====

    private static boolean domainMatches(String cookieDomain, Platform p) {
        String d = cookieDomain.toLowerCase();
        return switch (p) {
            case DOUYIN -> d.contains("douyin.com");
            case INSTAGRAM -> d.contains("instagram.com") || d.contains("cdninstagram.com");
            case YOUTUBE -> d.contains("youtube.com") || d.contains("google.com");
            case TWITTER -> d.contains("twitter.com") || d.contains("x.com");
            case TIKTOK -> d.contains("tiktok.com");
            case BILIBILI -> d.contains("bilibili.com");
            case CCTV -> d.contains("cctv.com") || d.contains("cntv.cn");
            default -> false;
        };
    }

    /**
     * 始终返回支持平台的完整列表（未配置的也返回占位状态）
     */
    public List<CookieStatus> listStatus(User user) {
        List<CookieStatus> result = new ArrayList<>();
        for (Platform p : List.of(Platform.YOUTUBE, Platform.DOUYIN, Platform.TWITTER,
                Platform.TIKTOK, Platform.INSTAGRAM, Platform.BILIBILI, Platform.CCTV)) {
            result.add(userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase())
                    .map(c -> new CookieStatus(p.name().toLowerCase(), p.display, true,
                            c.isValid(), isRequired(p), c.getStatusMessage(), c.getLastVerifiedAt(),
                            c.getLastUsedAt(), c.getUpdatedAt()))
                    .orElse(new CookieStatus(p.name().toLowerCase(), p.display, false,
                            false, isRequired(p), null, null, null, null)));
        }
        return result;
    }

    // ===== 下载链路使用 =====

    /**
     * 校验并保存用户上传的 cookies.txt 内容。校验不通过抛 BusinessException（不写库）。
     */
    public CookieStatus upload(User user, String platform, String content) {
        Platform p = resolvePlatform(platform);
        if (content == null || content.isBlank()) {
            throw new BusinessException("文件内容为空，请上传浏览器扩展导出的 cookies.txt");
        }
        if (content.length() > 512 * 1024) {
            throw new BusinessException("cookies 文件过大（上限 512KB），请确认导出的是正确的 cookies.txt");
        }
        List<ParsedCookie> cookies = parseNetscape(content);
        if (cookies.isEmpty()) {
            throw new BusinessException("未解析到有效 cookie，请确认是 Netscape 格式的 cookies.txt（浏览器扩展 Get cookies.txt LOCALLY 导出）");
        }
        String requiredName = REQUIRED_COOKIE.get(p);
        if (requiredName != null) {
            if (cookies.stream().noneMatch(c -> requiredName.equalsIgnoreCase(c.name()) && domainMatches(c.domain(), p))) {
                throw new BusinessException("cookie 中缺少 " + p.display + " 的登录态（" + requiredName
                        + "），请确认在已登录 " + p.display + " 的浏览器页面上导出");
            }
        } else {
            // 无特定登录态 cookie 名的平台（如央视网）：仅校验域名匹配
            if (cookies.stream().noneMatch(c -> domainMatches(c.domain(), p))) {
                throw new BusinessException("cookie 中未找到 " + p.display + " 域名的 cookie，请确认在已登录 "
                        + p.display + " 的浏览器页面上导出");
            }
        }

        String message;
        boolean valid;
        if (p == Platform.INSTAGRAM) {
            instagramVerifier.verify(toCookieHeader(content, Platform.INSTAGRAM));
            valid = true;
            message = "已校验，登录态有效";
        } else {
            // 其余平台对服务器 IP 有风控/无法可靠探活；保存时校验格式，失效由下载失败自动标记
            valid = true;
            message = "格式校验通过（" + p.display + "登录态将在实际下载时验证）";
        }

        LocalDateTime now = LocalDateTime.now();
        String platformKey = p.name().toLowerCase();
        UserCookie entity = userCookieMapper.selectByUserIdAndPlatform(user.getId(), platformKey)
                .orElseGet(() -> UserCookie.builder()
                        .userId(user.getId())
                        .platform(platformKey)
                        .createdAt(now)
                        .build());
        entity.setContent(content);
        entity.setValid(valid);
        entity.setStatusMessage(message);
        entity.setLastVerifiedAt(now);
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            userCookieMapper.insert(entity);
        } else {
            userCookieMapper.updateById(entity);
        }
        return new CookieStatus(platformKey, p.display, true, valid, isRequired(p), message,
                now, entity.getLastUsedAt(), now);
    }

    public void delete(User user, String platform) {
        Platform p = resolvePlatform(platform);
        userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase())
                .ifPresent(userCookieMapper::deleteById);
    }

    /**
     * 取当前用户某平台的 cookies 原文；未登录/未配置直接给明确业务错误。
     * 数据库不可用时（MySQL 未启动）返回 null，不阻塞下载。
     */
    public String requireContent(User user, Platform p) {
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "使用" + p.display + "下载需要先登录账号，请登录后上传该平台的 cookies");
        }
        UserCookie c;
        try {
            c = userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase()).orElse(null);
        } catch (Exception e) {
            log.warn("数据库不可用，跳过 cookies 获取: {}", e.getMessage());
            return null;
        }
        if (c == null || c.getContent() == null || c.getContent().isBlank()) {
            throw new BusinessException("尚未配置" + p.display + "的 cookies，请在「我的 Cookies」中上传 "
                    + p.display + " 页面导出的 cookies.txt");
        }
        try {
            c.setLastUsedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            userCookieMapper.updateById(c);
        } catch (Exception e) {
            log.warn("数据库不可用，跳过 cookies 更新: {}", e.getMessage());
        }
        return c.getContent();
    }

    /**
     * 非强制场景（如 AI 字幕）：取到则用，取不到返回 null。数据库不可用时返回 null。
     */
    public String findContent(User user, Platform p) {
        if (user == null) {
            return null;
        }
        try {
            return userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase())
                    .map(UserCookie::getContent).orElse(null);
        } catch (Exception e) {
            log.warn("数据库不可用，跳过 cookies 获取: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 下载/解析报鉴权类错误时，自动把该用户对应平台 cookie 标记为失效
     */
    public void markInvalidIfAuth(User user, Platform p, String errorMessage) {
        if (user == null || errorMessage == null) {
            return;
        }
        String lower = errorMessage.toLowerCase();
        boolean authError = lower.contains("cookie") || lower.contains("登录") || lower.contains("login")
                || errorMessage.contains("失效") || lower.contains("empty media") || lower.contains("sign in");
        if (!authError) {
            return;
        }
        userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase()).ifPresent(c -> {
            c.setValid(false);
            String msg = errorMessage.length() > 240 ? errorMessage.substring(0, 240) : errorMessage;
            c.setStatusMessage(msg);
            LocalDateTime now = LocalDateTime.now();
            c.setLastVerifiedAt(now);
            c.setUpdatedAt(now);
            userCookieMapper.updateById(c);
            log.info("用户 {} 的 {} cookies 已自动标记失效：{}", user.getId(), p.display, msg);
        });
    }

    // ===== Netscape cookies.txt 解析 =====

    /**
     * 明确判定 cookie 无效时（如 Instagram 登录门控）无条件标记失效
     */
    public void markInvalid(User user, Platform p, String errorMessage) {
        if (user == null) {
            return;
        }
        userCookieMapper.selectByUserIdAndPlatform(user.getId(), p.name().toLowerCase()).ifPresent(c -> {
            c.setValid(false);
            String msg = errorMessage == null ? "登录态已失效，请重新上传 cookies.txt"
                    : errorMessage.length() > 240 ? errorMessage.substring(0, 240) : errorMessage;
            c.setStatusMessage(msg);
            LocalDateTime now = LocalDateTime.now();
            c.setLastVerifiedAt(now);
            c.setUpdatedAt(now);
            userCookieMapper.updateById(c);
            log.info("用户 {} 的 {} cookies 已标记失效：{}", user.getId(), p.display, msg);
        });
    }

    /**
     * yt-dlp 子进程只接受文件：把库中原文落到临时文件，调用方用完删除
     */
    public Path materializeCookieFile(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            Path file = Files.createTempFile("fvd-cookie-", ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new BusinessException("cookies 临时文件写入失败：" + e.getMessage());
        }
    }

    private Platform resolvePlatform(String platform) {
        if (platform == null) {
            throw new BusinessException("平台参数不能为空");
        }
        Platform p;
        try {
            p = Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("不支持的平台：" + platform);
        }
        if (!SUPPORTED.contains(p)) {
            throw new BusinessException("该平台不支持自定义 cookies");
        }
        return p;
    }

    // ===== 工具 =====

    public record CookieStatus(String platform, String platformName, boolean configured,
                               boolean valid, boolean required, String statusMessage,
                               LocalDateTime lastVerifiedAt, LocalDateTime lastUsedAt,
                               LocalDateTime updatedAt) {
    }

    public record ParsedCookie(String domain, String name, String value) {
    }
}
