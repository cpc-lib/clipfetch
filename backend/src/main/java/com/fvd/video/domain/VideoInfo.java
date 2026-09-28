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
        boolean hasSubtitles
) {
}
