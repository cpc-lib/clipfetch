package com.fvd.parser.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个可下载格式选项
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormatInfo(
        String formatId,
        String ext,
        String resolution,
        Integer height,
        Long filesize,
        Long filesizeApprox,
        String vcodec,
        String acodec,
        String label,
        boolean needsMerge,
        boolean audioOnly,
        boolean serverOnly
) {
    /**
     * 常规格式默认允许浏览器直链
     */
    public FormatInfo(String formatId, String ext, String resolution, Integer height,
                      Long filesize, Long filesizeApprox, String vcodec, String acodec,
                      String label, boolean needsMerge, boolean audioOnly) {
        this(formatId, ext, resolution, height, filesize, filesizeApprox, vcodec, acodec,
                label, needsMerge, audioOnly, false);
    }
}
