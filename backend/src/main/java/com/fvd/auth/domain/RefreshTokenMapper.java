package com.fvd.auth.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    Optional<RefreshToken> selectByTokenAndRevokedFalse(String token);

    int deleteByExpiresAtBefore(LocalDateTime time);
}