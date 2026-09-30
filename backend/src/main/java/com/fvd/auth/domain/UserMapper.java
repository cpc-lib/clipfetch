package com.fvd.auth.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    Optional<User> selectByEmail(String email);

    boolean existsByEmail(String email);
}