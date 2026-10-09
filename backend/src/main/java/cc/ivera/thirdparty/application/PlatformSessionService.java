package cc.ivera.thirdparty.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cc.ivera.thirdparty.domain.ThirdPartySession;
import cc.ivera.thirdparty.domain.ThirdPartySessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 第三方平台令牌会话存储（SQLite/MySQL，按用户 + 平台标签隔离）。
 * 每次调用直接读库，不做进程内缓存。
 */
@Service
@RequiredArgsConstructor
public class PlatformSessionService {

    private final ThirdPartySessionMapper mapper;

    public Optional<ThirdPartySession> find(Long userId, String platform) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ThirdPartySession>()
                .eq("user_id", userId)
                .eq("platform", platform)
                .last("limit 1")));
    }

    /** 新建或覆盖某用户在某平台的令牌会话 */
    public void save(Long userId, String platform, String dualTokenType,
                     String accessToken, String refreshToken) {
        LocalDateTime now = LocalDateTime.now();
        ThirdPartySession existing = find(userId, platform).orElse(null);
        if (existing == null) {
            mapper.insert(ThirdPartySession.builder()
                    .userId(userId).platform(platform).dualTokenType(dualTokenType)
                    .accessToken(accessToken).refreshToken(refreshToken)
                    .createdAt(now).updatedAt(now)
                    .build());
        } else {
            existing.setDualTokenType(dualTokenType);
            existing.setAccessToken(accessToken);
            existing.setRefreshToken(refreshToken);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
        }
    }

    public void delete(Long userId, String platform) {
        mapper.delete(new QueryWrapper<ThirdPartySession>()
                .eq("user_id", userId).eq("platform", platform));
    }
}
