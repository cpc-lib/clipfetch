package com.fvd.auth.interfaces;

import com.fvd.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import com.fvd.auth.application.JwtService;
import com.fvd.auth.domain.User;
import com.fvd.auth.domain.UserRepository;

/**
 * 认证拦截器：校验 Bearer access token，将当前用户放入 request attribute。
 * 强制路径：/api/auth/me、/api/summarize、/api/chat、/api/cookies/**（无 token 直接 401）
 * 可选路径：/api/parse、/api/download、/api/direct-url（有 token 则解析用户，无则匿名放行，
 *          抖音/Instagram 是否必须登录由业务层按平台判断）
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "currentUser";

    private static final Set<String> OPTIONAL_PATHS =
            Set.of("/api/parse", "/api/download", "/api/direct-url");

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) {
        boolean optional = OPTIONAL_PATHS.contains(request.getRequestURI());
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            if (optional) {
                return true;
            }
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        var claims = jwtService.parseAccessToken(auth.substring(7));
        if (claims == null) {
            if (optional) {
                return true;
            }
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        User user = userRepository.findById(Long.valueOf(claims.getSubject())).orElse(null);
        if (user == null) {
            if (optional) {
                return true;
            }
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在，请重新登录");
        }
        request.setAttribute(ATTR_USER, user);
        return true;
    }
}
