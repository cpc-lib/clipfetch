package cc.ivera.ftp.interfaces;

import cc.ivera.ftp.infrastructure.FtpProperties;
import cc.ivera.ftp.interfaces.FtpDtos.EngineHealthResponse;
import cc.ivera.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ftp/engine")
@RequiredArgsConstructor
public class FtpHealthController {

    private final FtpProperties properties;

    @GetMapping("/health")
    public ApiResponse<EngineHealthResponse> health(HttpServletRequest request) {
        FtpController.currentUserId(request);
        boolean configured = properties.isConfigured();
        String version = FTPClient.class.getPackage().getImplementationVersion();
        return ApiResponse.ok(new EngineHealthResponse(
                configured,
                configured,
                version != null ? version : "unknown",
                configured ? "FTP/FTPS 下载模块已就绪" : "FTP/FTPS 下载模块未启用或配置不完整"
        ));
    }
}
