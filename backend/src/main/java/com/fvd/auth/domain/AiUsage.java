package com.fvd.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/**
 * AI 总结/问答每日使用量（免费用户限额用）
 */
@Entity
@Table(name = "ai_usage",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_usage_user_date", columnNames = {"user_id", "usage_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int count;
}
