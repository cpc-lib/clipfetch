package com.fvd.cookie.interfaces;

import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.auth.domain.User;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fvd.cookie.application.CookieService;

/**
 * 用户自助维护平台 cookies：查询状态 / 上传 cookies.txt / 删除。
 */
@RestController
@RequestMapping("/api/cookies")
@RequiredArgsConstructor
public class CookieController {

    private final CookieService cookieService;

    /** 当前用户两个平台的 cookie 状态 */
    @GetMapping
    public ApiResponse<List<CookieService.CookieStatus>> list(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user) {
        return ApiResponse.ok(cookieService.listStatus(user));
    }

    /** 上传 cookies.txt（multipart 字段名 file），保存时即时校验 */
    @PostMapping("/{platform}")
    public ApiResponse<CookieService.CookieStatus> upload(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user,
            @PathVariable String platform,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的 cookies.txt 文件");
        }
        String original = file.getOriginalFilename();
        if (original != null && !original.toLowerCase().endsWith(".txt")) {
            throw new BusinessException("请上传 .txt 格式的 cookies 文件");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException("文件读取失败：" + e.getMessage());
        }
        return ApiResponse.ok(cookieService.upload(user, platform, content));
    }

    @DeleteMapping("/{platform}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthInterceptor.ATTR_USER) User user,
            @PathVariable String platform) {
        cookieService.delete(user, platform);
        return ApiResponse.ok(null);
    }
}
