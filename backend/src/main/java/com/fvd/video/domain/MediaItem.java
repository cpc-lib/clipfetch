package com.fvd.video.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 轮播帖媒体项（Instagram 等）：type = image | video
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaItem(
        String type,
        String url,
        /** 视频封面帧（图片项为 null） */
        String cover,
        Integer width,
        Integer height
) {
}
