package com.fvd.temp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 临时子链接：保存集合页（如网易云歌手页）解析出的子链接。
 */
@TableName("temp")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TempLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 子链接（如歌曲链接） */
    private String url;

    /** 子链接标题（如歌曲名） */
    private String title;

    /** 来源集合链接（如歌手页/歌单页） */
    private String sourceUrl;

    /** 是否已下载：0=未下载 1=已下载 */
    private Boolean downloaded;

    private LocalDateTime createdAt;
}
