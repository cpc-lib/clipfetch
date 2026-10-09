package com.fvd.preparse.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.preparse.domain.PreParse;
import com.fvd.preparse.domain.PreParseMapper;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预解析库：用户预保存视频链接，解析成功后标记已解析。
 */
@RestController
@RequestMapping("/api/pre-parse")
@RequiredArgsConstructor
public class PreParseController {

    private final PreParseMapper preParseMapper;

    /**
     * 分页查询预解析库列表。
     * url 精确匹配，keyword 模糊匹配 title，parsed 精确匹配。
     */
    @GetMapping("/list")
    public ApiResponse<Page<PreParse>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean parsed,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) String keyword) {
        Long userId = currentUserId(request);
        QueryWrapper<PreParse> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (parsed != null) {
            qw.eq("parsed", parsed);
        }
        if (url != null && !url.isBlank()) {
            qw.eq("url", url.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like("title", keyword.trim());
        }
        qw.orderByDesc("created_at");
        Page<PreParse> result = preParseMapper.selectPage(new Page<>(page, size), qw);
        return ApiResponse.ok(result);
    }

    /**
     * 新增预解析记录。URL 非空，同用户下未删除记录 URL 唯一。
     */
    @PostMapping("/add")
    public ApiResponse<PreParse> add(HttpServletRequest request, @RequestBody AddReq req) {
        Long userId = currentUserId(request);
        String url = req.getUrl() == null ? "" : req.getUrl().trim();
        if (url.isEmpty()) {
            throw new BusinessException("链接不能为空");
        }
        // 同用户下未删除记录 URL 唯一
        QueryWrapper<PreParse> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("url", url);
        if (preParseMapper.selectCount(qw) > 0) {
            throw new BusinessException("该链接已存在");
        }
        String title = req.getTitle() == null ? "" : req.getTitle().trim();
        if (title.isEmpty()) {
            title = generateTitle(url);
        }
        PreParse entity = PreParse.builder()
                .userId(userId)
                .url(url)
                .title(title)
                .parsed(false)
                .deleted(false)
                .createdAt(LocalDateTime.now())
                .build();
        preParseMapper.insert(entity);
        return ApiResponse.ok(entity);
    }

    /**
     * 逻辑删除指定记录。deleted 设为记录 id（非固定 1），避免唯一索引冲突。
     */
    @PostMapping("/delete")
    public ApiResponse<Void> delete(HttpServletRequest request, @RequestBody DeleteReq req) {
        Long userId = currentUserId(request);
        if (req.getIds() == null || req.getIds().isEmpty()) {
            throw new BusinessException("请选择要删除的记录");
        }
        for (Long id : req.getIds()) {
            preParseMapper.logicalDeleteById(id, userId);
        }
        return ApiResponse.ok(null);
    }

    /**
     * 标记指定 URL 为已解析。
     */
    @PostMapping("/mark-parsed")
    public ApiResponse<Void> markParsed(HttpServletRequest request, @RequestBody MarkReq req) {
        Long userId = currentUserId(request);
        String url = req.getUrl() == null ? "" : req.getUrl().trim();
        if (url.isEmpty()) {
            throw new BusinessException("链接不能为空");
        }
        QueryWrapper<PreParse> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("url", url).eq("parsed", false);
        List<PreParse> records = preParseMapper.selectList(qw);
        for (PreParse r : records) {
            r.setParsed(true);
            preParseMapper.updateById(r);
        }
        return ApiResponse.ok(null);
    }

    private Long currentUserId(HttpServletRequest request) {
        User user = (User) request.getAttribute(AuthInterceptor.ATTR_USER);
        return user.getId();
    }

    /** 从 URL 提取最后一段路径作为默认标题 */
    private String generateTitle(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isBlank()) {
                    String t = segments[i];
                    return t.length() > 64 ? t.substring(0, 64) : t;
                }
            }
        } catch (Exception ignored) {
        }
        return url.length() > 64 ? url.substring(0, 64) : url;
    }

    @Data
    public static class AddReq {
        private String url;
        private String title;
    }

    @Data
    public static class DeleteReq {
        private List<Long> ids;
    }

    @Data
    public static class MarkReq {
        private String url;
    }
}
