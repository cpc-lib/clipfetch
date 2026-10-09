package cc.ivera.cookie.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface UserCookieMapper extends BaseMapper<UserCookie> {

    Optional<UserCookie> selectByUserIdAndPlatform(@Param("userId") Long userId, @Param("platform") String platform);

    List<UserCookie> selectByUserId(Long userId);
}