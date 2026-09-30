package com.fvd.auth.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.Optional;

@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    Optional<AiUsage> selectByUserIdAndUsageDate(Long userId, LocalDate usageDate);
}