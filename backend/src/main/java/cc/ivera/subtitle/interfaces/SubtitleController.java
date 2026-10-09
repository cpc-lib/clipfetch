package cc.ivera.subtitle.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import cc.ivera.subtitle.application.SubtitleGatewayService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 字幕转换：登录态网关。用户须先用自己的第三方账号「连接」，
 * 数据不在本系统落地，第三方返回的 data 原样透传（见 {@link SubtitleGatewayService}）。
 */
@RestController
@RequestMapping("/api/subtitles")
@RequiredArgsConstructor
public class SubtitleController {

    private static final Set<String> ALLOWED_EXT = Set.of("srt", "vtt", "ass");

    private final SubtitleGatewayService gateway;

    /** 连接字幕服务入参（用户自己的第三方账号；非空校验在网关登录时统一处理） */
    public record ConnectReq(String username, String password, String tenantCode) {
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 当前第三方连接状态 */
    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> session(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        return ApiResponse.ok(Map.of("connected", gateway.isConnected(user.getId())));
    }

    /** 用第三方账号连接（登录换取令牌并保存会话，覆盖旧会话） */
    @PostMapping("/session/connect")
    public ApiResponse<Map<String, Object>> connect(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user,
            @RequestBody ConnectReq req) {
        gateway.connect(user.getId(), trim(req.username()), req.password(), trim(req.tenantCode()));
        return ApiResponse.ok(Map.of("connected", true));
    }

    /** 断开连接（删除令牌会话） */
    @DeleteMapping("/session")
    public ApiResponse<Map<String, Object>> disconnect(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        gateway.disconnect(user.getId());
        return ApiResponse.ok(Map.of("connected", false));
    }

    /** 当前用户第三方账号下的字幕历史记录 */
    @GetMapping
    public ApiResponse<JsonNode> list(@RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        return ApiResponse.ok(gateway.list(user.getId()));
    }

    /** 翻译目标语言列表（租户维护） */
    @GetMapping("/langs")
    public ApiResponse<JsonNode> langs(@RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        return ApiResponse.ok(gateway.langs(user.getId()));
    }

    /** 上传字幕文件（.srt / .vtt / .ass） */
    @PostMapping
    public ApiResponse<JsonNode> upload(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                                        @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的字幕文件");
        }
        String original = file.getOriginalFilename();
        String ext = original != null && original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("仅支持 SRT / VTT / ASS 格式字幕文件");
        }
        return ApiResponse.ok(gateway.upload(user.getId(), file));
    }

    /** 字幕详情（含全部字幕条） */
    @GetMapping("/{id}")
    public ApiResponse<JsonNode> detail(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                                        @PathVariable long id) {
        return ApiResponse.ok(gateway.detail(user.getId(), id));
    }

    /** 翻译字幕（body 原样透传：targetLang / indices） */
    @PostMapping("/{id}/translate")
    public ApiResponse<JsonNode> translate(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                                           @PathVariable long id,
                                           @RequestBody String body) {
        return ApiResponse.ok(gateway.translate(user.getId(), id, body));
    }

    /** 保存人工编辑（body 原样透传：cues 全量覆盖） */
    @PutMapping("/{id}")
    public ApiResponse<JsonNode> update(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                                        @PathVariable long id,
                                        @RequestBody String body) {
        return ApiResponse.ok(gateway.update(user.getId(), id, body));
    }

    /** 删除字幕记录 */
    @DeleteMapping("/{id}")
    public ApiResponse<JsonNode> delete(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                                        @PathVariable long id) {
        return ApiResponse.ok(gateway.delete(user.getId(), id));
    }

    /** 下载 SRT（第三方二进制透传，有译文取译文，无译文取原文） */
    @GetMapping("/{id}/download")
    public void download(@RequestAttribute(AuthInterceptor.ATTR_USER) User user,
                         @PathVariable long id,
                         HttpServletResponse response) {
        SubtitleGatewayService.SrtFile srt = gateway.download(user.getId(), id);
        String encoded = URLEncoder.encode(srt.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(srt.contentType());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(srt.body().length);
        try {
            response.getOutputStream().write(srt.body());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new BusinessException("字幕下载失败：" + e.getMessage());
        }
    }
}
