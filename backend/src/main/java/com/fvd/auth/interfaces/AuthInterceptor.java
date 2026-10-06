package com.fvd.auth.interfaces;

import com.fvd.auth.application.JwtService;
import com.fvd.auth.domain.User;
import com.fvd.auth.domain.UserMapper;
import com.fvd.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：校验 Bearer access token，将当前用户放入 request attribute。
 * 所有已注册拦截的路径均强制认证（无匿名例外）。
 * 登录/注册接口（/api/auth/login、/api/auth/register、/api/auth/refresh）未注册拦截，允许匿名。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "currentUser";

    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        var claims = jwtService.parseAccessToken(auth.substring(7));
        if (claims == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        User user;
        try {
            user = userMapper.selectById(Long.valueOf(claims.getSubject()));
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在，请重新登录");
        }
        request.setAttribute(ATTR_USER, user);
        return true;
    }
}
