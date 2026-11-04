package cc.ivera.shared.config;

import cc.ivera.auth.interfaces.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全局认证：除登录/注册/刷新外，所有 /api/** 均强制登录
        // wallpaper/image-proxy 由 <img> 标签直接请求（无法携带 Authorization 头），必须免登录
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        // Browser-native downloads use a short-lived file-scoped ticket, not a JWT URL.
                        "/api/magnet/downloads/stream/*",
                        "/api/wallpaper",
                        "/api/image-proxy");
    }
}
