package com.fvd.cookie.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用户为指定平台上传的登录 cookies（Netscape cookies.txt 原文）。
 */
@TableName("user_cookie")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCookie {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 平台标识：douyin / instagram（对应 Platform 枚举小写名）
     */
    private String platform;

    private String content;

    private boolean valid;

    private String statusMessage;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}