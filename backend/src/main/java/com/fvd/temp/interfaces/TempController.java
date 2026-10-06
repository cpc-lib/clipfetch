package com.fvd.temp.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fvd.shared.web.ApiResponse;
import com.fvd.shared.web.BusinessException;
import com.fvd.temp.domain.TempLink;
import com.fvd.temp.domain.TempLinkMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 网易云音乐文件库：查询 temp 表中保存的歌曲子链接。
 */
@RestController
@RequestMapping("/api/temp")
@RequiredArgsConstructor
public class TempController {

    private final TempLinkMapper tempLinkMapper;

    /**
     * 分页查询文件库列表，支持按下载状态和文件名过滤。
     */
    @GetMapping("/list")
    public ApiResponse<Page<TempLink>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean downloaded,
            @RequestParam(required = false) String keyword) {
        QueryWrapper<TempLink> qw = new QueryWrapper<>();
        if (downloaded != null) {
            qw.eq("downloaded", downloaded);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("title", keyword).or().like("url", keyword));
        }
        qw.orderByDesc("created_at");
        Page<TempLink> result = tempLinkMapper.selectPage(new Page<>(page, size), qw);
        return ApiResponse.ok(result);
    }

    /**
     * 逻辑删除指定记录（置 deleted=1，物理行保留）。
     */
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody DeleteReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            throw new BusinessException("请选择要删除的记录");
        }
        tempLinkMapper.deleteBatchIds(req.getIds());
        return ApiResponse.ok(null);
    }

    @Data
    public static class DeleteReq {
        private List<Long> ids;
    }
}
