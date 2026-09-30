package com.fvd.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDate;

/**
 * AI 总结/问答每日使用量（免费用户限额用）
 */
@TableName("ai_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate usageDate;

    private int count;
}