package cc.ivera.ed2k.interfaces;

import cc.ivera.ed2k.application.Ed2kTaskService;
import cc.ivera.ed2k.infrastructure.Ed2kEngineClient.EngineHealth;
import cc.ivera.ed2k.interfaces.Ed2kDtos.EngineAccelerationResponse;
import cc.ivera.ed2k.interfaces.Ed2kDtos.EngineHealthResponse;
import cc.ivera.shared.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ed2k/engine")
@RequiredArgsConstructor
public class Ed2kHealthController {

    private final Ed2kTaskService taskService;

    @GetMapping("/health")
    public ApiResponse<EngineHealthResponse> health() {
        EngineHealth health = taskService.getEngineHealth();
        return ApiResponse.ok(new EngineHealthResponse(
                health.configured(),
                health.connected(),
                health.version(),
                health.message(),
                health.ed2kConnected(),
                health.highId(),
                health.kadConnected(),
                health.downloadSourceCount()
        ));
    }

    @PostMapping("/accelerate")
    public ApiResponse<EngineAccelerationResponse> accelerateEngine() {
        return ApiResponse.ok(taskService.accelerateEngine());
    }
}
