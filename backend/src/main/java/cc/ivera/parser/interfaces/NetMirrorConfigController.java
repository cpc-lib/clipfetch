package cc.ivera.parser.interfaces;

import cc.ivera.parser.application.parser.video.NetMirrorTmdbKeyService;
import cc.ivera.shared.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NetMirror TMDB Key 管理接口：查询脱敏状态、手动抓取覆盖、手动录入兜底。
 * 全部端点强制 JWT 登录（不在 WebConfig 白名单内），响应只返回脱敏 Key。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/netmirror/tmdb-keys")
public class NetMirrorConfigController {

    private final NetMirrorTmdbKeyService tmdbKeyService;

    /** 只读查询当前 Key 状态，不触发官网抓取。 */
    @GetMapping
    public ApiResponse<NetMirrorTmdbKeyService.TmdbKeyStatus> status() {
        return ApiResponse.ok(tmdbKeyService.getStatus());
    }

    /** 立即从 NetMirror 官网抓取 JS、提取验活并覆盖数据库，绕过自动刷新冷却。 */
    @PostMapping("/refresh")
    public ApiResponse<NetMirrorTmdbKeyService.TmdbKeyStatus> refresh() {
        return ApiResponse.ok(tmdbKeyService.manualRefresh());
    }

    /** 手动写入/覆盖 TMDB Key 列表（兜底），默认先验活再覆盖。 */
    @PutMapping
    public ApiResponse<NetMirrorTmdbKeyService.TmdbKeyStatus> saveManual(
            @Valid @RequestBody SaveKeysRequest req) {
        boolean verify = req.verify() == null || req.verify();
        return ApiResponse.ok(tmdbKeyService.saveManualKeys(req.keys(), verify));
    }

    public record SaveKeysRequest(
            @NotEmpty(message = "TMDB Key 列表不能为空")
            @Size(max = 10, message = "TMDB Key 数量不能超过 10 个")
            List<String> keys,
            Boolean verify) {
    }
}
