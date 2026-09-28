package com.fvd.auth.application;

import com.fvd.auth.domain.AiUsage;
import com.fvd.auth.domain.AiUsageRepository;
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

    private final AiUsageRepository aiUsageRepository;

    @Value("${app.ai.free-daily-quota:3}")
    private int freeDailyQuota;

    /**
     * 校验并消耗一次额度，超额抛出异常
     */
    @Transactional
    public void checkAndConsume(User user) {
        if (user.isVip()) {
            return;
        }
        LocalDate today = LocalDate.now();
        AiUsage usage = aiUsageRepository.findByUserIdAndUsageDate(user.getId(), today)
                .orElseGet(() -> AiUsage.builder()
                        .userId(user.getId())
                        .usageDate(today)
                        .count(0)
                        .build());
        if (usage.getCount() >= freeDailyQuota) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "今日免费次数已用完（" + freeDailyQuota + " 次/天），开通 VIP 可无限使用");
        }
        usage.setCount(usage.getCount() + 1);
        aiUsageRepository.save(usage);
    }
}
