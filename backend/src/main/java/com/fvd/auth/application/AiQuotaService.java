package com.fvd.auth.application;

import com.fvd.auth.domain.AiUsage;
import com.fvd.auth.domain.AiUsageMapper;
import com.fvd.auth.domain.User;
import com.fvd.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * AI 使用配额：免费用户每日 N 次，VIP 不限
 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiUsageMapper aiUsageMapper;

    @Value("${app.ai.free-daily-quota:3}")
    private int freeDailyQuota;

    /**
     * 校验并消耗一次额度，超额抛出异常。
     * 匿名用户（user == null）直接放行，不做配额限制。
     */
    @Transactional
    public void checkAndConsume(User user) {
        if (user == null) {
            return;
        }
        if (user.isVip()) {
            return;
        }
        LocalDate today = LocalDate.now();
        AiUsage usage;
        try {
            usage = aiUsageMapper.selectByUserIdAndUsageDate(user.getId(), today)
                    .orElseGet(() -> AiUsage.builder()
                            .userId(user.getId())
                            .usageDate(today)
                            .count(0)
                            .build());
        } catch (Exception e) {
            // 数据库不可用时放行，不阻塞 AI 功能
            return;
        }
        if (usage.getCount() >= freeDailyQuota) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "今日免费次数已用完（" + freeDailyQuota + " 次/天），开通 VIP 可无限使用");
        }
        try {
            usage.setCount(usage.getCount() + 1);
            if (usage.getId() == null) {
                aiUsageMapper.insert(usage);
            } else {
                aiUsageMapper.updateById(usage);
            }
        } catch (Exception e) {
            // 数据库不可用时放行，不阻塞 AI 功能
        }
    }
}
