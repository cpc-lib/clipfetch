package com.fvd.cookie.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用户为指定平台上传的登录 cookies（Netscape cookies.txt 原文）。
 */
@Entity
@Table(name = "user_cookie",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_cookie_user_platform",
                columnNames = {"user_id", "platform"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCookie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 平台标识：douyin / instagram（对应 Platform 枚举小写名）
     */
    @Column(nullable = false, length = 16)
    private String platform;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false)
    private boolean valid;

    @Column(name = "status_message", length = 255)
    private String statusMessage;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
