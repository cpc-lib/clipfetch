package com.fvd.auth.interfaces;

import com.fvd.auth.application.JwtService;
import com.fvd.auth.domain.RefreshToken;
import com.fvd.auth.domain.RefreshTokenMapper;
import com.fvd.auth.domain.User;
import com.fvd.auth.domain.UserMapper;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    @Value("${app.jwt.refresh-expire-days}")
    private long refreshExpireDays;

    // ===== DTO =====

    @PostMapping("/register")
    @Transactional
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterReq req) {
        String email = req.getEmail().trim().toLowerCase();
        if (req.getPassword().length() < 6) {
            throw new BusinessException("密码至少 6 位");
        }
        if (userMapper.existsByEmail(email)) {
            throw new BusinessException("该邮箱已注册");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(encoder.encode(req.getPassword()))
                .nickname(nicknameOrEmail(req.getNickname(), email))
                .vip(false)
                .createdAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        log.info("新用户注册: {}", email);
        return ApiResponse.ok(tokenPair(user));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginReq req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userMapper.selectByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (!encoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        return ApiResponse.ok(tokenPair(user));
    }

    /**
     * 双 token 刷新：校验 refresh token（未过期未吊销），旋转发放新 token 对
     */
    @PostMapping("/refresh")
    @Transactional
    public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody RefreshReq req) {
        RefreshToken rt = refreshTokenMapper.selectByTokenAndRevokedFalse(req.getRefreshToken())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录状态失效，请重新登录"));
        if (rt.getExpiresAt().isBefore(LocalDateTime.now())) {
            rt.setRevoked(true);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        rt.setRevoked(true); // 旋转：旧 refresh token 立即失效
        User user = userMapper.selectById(rt.getUserId());
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }
        return ApiResponse.ok(tokenPair(user));
    }

    // ===== 接口 =====

    @PostMapping("/logout")
    @Transactional
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshReq req) {
        refreshTokenMapper.selectByTokenAndRevokedFalse(req.getRefreshToken())
                .ifPresent(rt -> {
                    rt.setRevoked(true);
                    refreshTokenMapper.updateById(rt);
                });
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me(@RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        return ApiResponse.ok(UserVO.from(user));
    }

    private Map<String, Object> tokenPair(User user) {
        String refresh = HexFormat.of().formatHex(newTokenBytes());
        RefreshToken rt = RefreshToken.builder()
                .userId(user.getId())
                .token(refresh)
                .expiresAt(LocalDateTime.now().plusDays(refreshExpireDays))
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .build();
        refreshTokenMapper.insert(rt);
        return Map.of(
                "accessToken", jwtService.generateAccessToken(user),
                "refreshToken", refresh,
                "user", UserVO.from(user)
        );
    }

    private byte[] newTokenBytes() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return bytes;
    }

    private String nicknameOrEmail(String nickname, String email) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    // ===== 内部 =====

    @Data
    public static class RegisterReq {
        @NotBlank
        @Email
        private String email;
        @NotBlank
        private String password;
        private String nickname;
    }

    @Data
    public static class LoginReq {
        @NotBlank
        @Email
        private String email;
        @NotBlank
        private String password;
    }

    @Data
    public static class RefreshReq {
        @NotBlank
        private String refreshToken;
    }

    public record UserVO(Long id, String email, String nickname, boolean vip) {
        public static UserVO from(User user) {
            return new UserVO(user.getId(), user.getEmail(), user.getNickname(), user.isVip());
        }
    }
}
