package com.fvd.subtitle.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import com.fvd.thirdparty.application.PlatformSessionService;
import com.fvd.thirdparty.domain.ThirdPartySession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第三方字幕转换服务（RAG API）网关。
 * <p>
 * ClipFetch 登录用户用自己在第三方平台的账号（用户名/密码/租户编码）发起「连接」，
 * 后端登录换取令牌后按 用户+平台标签（rag_subtitle）存入 third_party_session 表，
 * 账号密码不落库。后续请求透传该用户的令牌；第三方返回 401 时删除会话并要求重新连接。
 * 第三方统一响应 {@code {code,message,data}}（code=0 成功），data 原样透传给前端。
 */
@Slf4j
@Service
public class SubtitleGatewayService {

    /** 第三方平台标签 */
    public static final String PLATFORM = "rag_subtitle";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final PlatformSessionService sessions;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SubtitleGatewayService(
            @Value("${app.subtitle.api-base-url:http://localhost:8080}") String baseUrl,
            PlatformSessionService sessions) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sessions = sessions;
    }

    // ==================== 会话连接 ====================

    public boolean isConnected(Long userId) {
        return sessions.find(userId, PLATFORM).isPresent();
    }

    /** 用用户自己的第三方账号登录，成功后保存令牌会话（覆盖旧会话） */
    public void connect(Long userId, String username, String password, String tenantCode) {
        String token = login(username, password, tenantCode);
        sessions.save(userId, PLATFORM, ThirdPartySession.TYPE_SINGLE, token, null);
        log.info("用户 {} 已连接字幕服务账号 {}", userId, username);
    }

    public void disconnect(Long userId) {
        sessions.delete(userId, PLATFORM);
    }

    // ==================== 业务代理 ====================

    /** 当前用户第三方账号下的字幕历史列表（data 原样透传） */
    public JsonNode list(Long userId) {
        return jsonExchange(userId, "GET", "/api/v1/subtitles", null, false);
    }

    /** 租户翻译目标语言列表 */
    public JsonNode langs(Long userId) {
        return jsonExchange(userId, "GET", "/api/v1/subtitles/langs", null, false);
    }

    /** 字幕详情（含全部字幕条） */
    public JsonNode detail(Long userId, long id) {
        return jsonExchange(userId, "GET", "/api/v1/subtitles/" + id, null, false);
    }

    /** 上传字幕文件（multipart 字段 file） */
    public JsonNode upload(Long userId, MultipartFile file) {
        try {
            byte[] content = file.getBytes();
            String boundary = "----clipfetch" + System.currentTimeMillis();
            HttpRequest request = authedBuilder(userId, "/api/v1/subtitles", Duration.ofMinutes(5))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            buildMultipart(boundary, file.getOriginalFilename(),
                                    file.getContentType(), content, sha256Hex(content))))
                    .build();
            HttpResponse<String> resp = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleJsonResponse(userId, resp);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("字幕文件上传失败：" + e.getMessage());
        }
    }

    /** 翻译字幕：jsonBody 为前端原始 JSON（targetLang / indices），LLM 翻译耗时较长 */
    public JsonNode translate(Long userId, long id, String jsonBody) {
        return jsonExchange(userId, "POST", "/api/v1/subtitles/" + id + "/translate", jsonBody, true);
    }

    /** 保存人工编辑：jsonBody 为前端原始 JSON（cues 全量覆盖） */
    public JsonNode update(Long userId, long id, String jsonBody) {
        return jsonExchange(userId, "PUT", "/api/v1/subtitles/" + id, jsonBody, false);
    }

    /** 删除字幕记录 */
    public JsonNode delete(Long userId, long id) {
        return jsonExchange(userId, "DELETE", "/api/v1/subtitles/" + id, null, false);
    }

    /** 下载 SRT（二进制透传） */
    public SrtFile download(Long userId, long id) {
        try {
            HttpResponse<byte[]> resp = http.send(
                    authedBuilder(userId, "/api/v1/subtitles/" + id + "/download", Duration.ofMinutes(2))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                String disposition = resp.headers().firstValue("Content-Disposition").orElse("");
                return new SrtFile(parseFilename(disposition, id),
                        resp.headers().firstValue("Content-Type")
                                .orElse("application/x-subrip;charset=UTF-8"),
                        resp.body());
            }
            JsonNode node = parseOrNull(new String(resp.body(), StandardCharsets.UTF_8));
            handleUpstreamError(userId, node, resp.statusCode());
            throw new BusinessException("字幕服务异常（HTTP " + resp.statusCode() + "）");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("字幕服务请求失败：" + e.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    /** 取出当前用户的第三方令牌；未连接时 428，提示前端展示连接表单 */
    private String requireToken(Long userId) {
        return sessions.find(userId, PLATFORM)
                .map(ThirdPartySession::getAccessToken)
                .orElseThrow(this::notConnected);
    }

    private BusinessException notConnected() {
        return new BusinessException(HttpStatus.PRECONDITION_REQUIRED,
                "未连接字幕服务，请先填写第三方账号信息");
    }

    private HttpRequest.Builder authedBuilder(Long userId, String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + requireToken(userId))
                .header("Accept", "application/json");
    }

    /**
     * JSON 请求统一出口。
     *
     * @param longOp 是否长耗时操作（翻译给 10 分钟，其余 2 分钟）
     */
    private JsonNode jsonExchange(Long userId, String method, String path, String jsonBody, boolean longOp) {
        Duration timeout = longOp ? Duration.ofMinutes(10) : Duration.ofMinutes(2);
        try {
            HttpRequest.Builder b = authedBuilder(userId, path, timeout);
            HttpRequest.BodyPublisher publisher = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
            if (jsonBody != null) {
                b.header("Content-Type", "application/json");
            }
            b.method(method, publisher);
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handleJsonResponse(userId, resp);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("字幕服务请求失败：" + e.getMessage());
        }
    }

    /** 解析统一响应：成功取 data；401 删除会话并要求重新连接；其余抛业务异常 */
    private JsonNode handleJsonResponse(Long userId, HttpResponse<String> resp) {
        JsonNode node = parseOrNull(resp.body());
        if (node == null) {
            throw new BusinessException("字幕服务返回异常（HTTP " + resp.statusCode() + "）");
        }
        int code = node.path("code").asInt(-1);
        if (code == 0) {
            return node.get("data");
        }
        handleUpstreamError(userId, node, resp.statusCode());
        throw upstreamError(node, resp.statusCode());
    }

    /**
     * 第三方令牌失效：单 token 平台无法自动续期（账号密码不落库），
     * 删除会话并以 428 通知前端重新连接。
     */
    private void handleUpstreamError(Long userId, JsonNode node, int httpStatus) {
        if (node != null && node.path("code").asInt(-1) == 401) {
            sessions.delete(userId, PLATFORM);
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED,
                    "字幕服务登录已过期，请重新连接");
        }
    }

    /** 用用户提交的第三方账号密码直接登录，失败时回传第三方错误信息 */
    private String login(String username, String password, String tenantCode) {
        if (username == null || username.isBlank()
                || password == null || password.isBlank()
                || tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException("请完整填写用户名、密码和租户编码");
        }
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "username", username, "password", password, "tenantCode", tenantCode));
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = parseOrNull(resp.body());
            if (node != null && node.path("code").asInt(-1) == 0 && node.path("data").has("token")) {
                return node.get("data").get("token").asText();
            }
            String message = node != null ? node.path("message").asText("登录失败")
                    : "HTTP " + resp.statusCode();
            throw new BusinessException("字幕服务登录失败：" + message);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("无法连接字幕服务（" + baseUrl + "）：" + e.getMessage());
        }
    }

    private BusinessException upstreamError(JsonNode node, int httpStatus) {
        String message = node != null ? node.path("message").asText("") : "";
        return new BusinessException(message.isBlank()
                ? "字幕服务异常（HTTP " + httpStatus + "）" : "字幕服务：" + message);
    }

    private JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 组装 multipart/form-data 报文（普通字段 sha256 + 文件字段 file） */
    private byte[] buildMultipart(String boundary, String filename, String contentType,
                                  byte[] content, String sha256) {
        String safeName = filename == null || filename.isBlank() ? "subtitle.srt" : filename;
        String partContentType = contentType != null && !contentType.isBlank()
                ? contentType : "application/octet-stream";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String shaPart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
                + sha256 + "\r\n";
        String fileHead = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + partContentType + "\r\n\r\n";
        try {
            out.write(shaPart.getBytes(StandardCharsets.UTF_8));
            out.write(fileHead.getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException("字幕文件读取失败：" + e.getMessage());
        }
        return out.toByteArray();
    }

    /** 计算文件字节的 SHA-256 小写十六进制（第三方字幕接口用于秒传/去重） */
    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PLAIN = Pattern.compile("filename=\"?([^\";]+)\"?");

    /** 从 Content-Disposition 解析文件名，优先 filename*=UTF-8'' */
    private String parseFilename(String disposition, long id) {
        if (disposition != null) {
            Matcher star = FILENAME_STAR.matcher(disposition);
            if (star.find()) {
                return URLDecoder.decode(star.group(1).trim(), StandardCharsets.UTF_8);
            }
            Matcher plain = FILENAME_PLAIN.matcher(disposition);
            if (plain.find()) {
                return plain.group(1).trim();
            }
        }
        return "subtitle-" + id + ".srt";
    }

    /** 下载得到的 SRT 文件 */
    public record SrtFile(String filename, String contentType, byte[] body) {
    }
}
