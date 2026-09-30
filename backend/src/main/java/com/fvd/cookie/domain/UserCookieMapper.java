package com.fvd.cookie.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface UserCookieMapper extends BaseMapper<UserCookie> {

    Optional<UserCookie> selectByUserIdAndPlatform(Long userId, String platform);

    List<UserCookie> selectByUserId(Long userId);
}