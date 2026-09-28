package com.fvd.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Optional<AiUsage> findByUserIdAndUsageDate(Long userId, LocalDate usageDate);
}
