package cc.ivera.magnet.v2.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.engine.spi.NativeV2Health;
import cc.ivera.magnet.v2.infrastructure.config.NativeV2Properties;
import cc.ivera.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 健康检查始终返回 HTTP 200，引擎离线或未启用时只降级字段。 */
@RestController
@RequestMapping("/api/magnet/v2")
@RequiredArgsConstructor
public class NativeV2HealthController {

    private final NativeV2Properties properties;
    private final Optional<NativeV2Engine> engine;

    @GetMapping("/engine/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        currentUserId(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", properties.isEnabled());
        if (!properties.isEnabled()) {
            data.put("connected", false);
            data.put("version", null);
            data.put("message", "Native v2 未启用");
            return ApiResponse.ok(data);
        }
        NativeV2Health health = engine
                .map(NativeV2Engine::health)
                .orElseGet(() -> NativeV2Health.unavailable("Native v2 Worker暂不可用"));
        data.put("connected", health.connected());
        data.put("version", health.version().isEmpty() ? null : health.version());
        data.put("message", health.message());
        return ApiResponse.ok(data);
    }

    private static long currentUserId(HttpServletRequest request) {
        User user = (User) request.getAttribute(AuthInterceptor.ATTR_USER);
        return user.getId();
    }
}
