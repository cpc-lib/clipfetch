package com.fvd.video.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 视频解析结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoInfo(
        String id,
        String title,
        String thumbnail,
        Long duration,
        String durationString,
        String uploader,
        String platform,
        Long viewCount,
        String uploadDate,
        List<FormatInfo> formats,
        List<MediaItem> media,
        List<String> subtitles,
        boolean hasSubtitles,
        // 正文/简介（如图文帖描述），仅部分平台返回；追加在末尾以兼容既有 13 参构造
        String description
) {
    /** 无正文时的兼容构造器 */
    public VideoInfo(String id, String title, String thumbnail, Long duration, String durationString,
                     String uploader, String platform, Long viewCount, String uploadDate,
                     List<FormatInfo> formats, List<MediaItem> media,
                     List<String> subtitles, boolean hasSubtitles) {
        this(id, title, thumbnail, duration, durationString, uploader, platform, viewCount, uploadDate,
                formats, media, subtitles, hasSubtitles, null);
    }
}
