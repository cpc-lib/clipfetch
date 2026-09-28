package com.fvd.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 字幕结构：分段列表 + 全文
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubtitleData(
        String language,
        List<Segment> segments,
        String fullText
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            double start,
            double end,
            String startDisplay,
            String endDisplay,
            String text
    ) {
    }
}
