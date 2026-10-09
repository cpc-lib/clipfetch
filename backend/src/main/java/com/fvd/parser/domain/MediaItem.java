package com.fvd.parser.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 轮播帖媒体项：type = image | video | live（小红书实况图，静图 + 短视频配对）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaItem(
        String type,
        String url,
        /** 视频封面帧（图片项为 null） */
        String cover,
        Integer width,
        Integer height,
        /** 实况图（live）配套短视频地址，其余类型为 null */
        String videoUrl
) {
    /** 无实况视频时的兼容构造器 */
    public MediaItem(String type, String url, String cover, Integer width, Integer height) {
        this(type, url, cover, width, height, null);
    }
}
