package cc.ivera.thirdparty.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 第三方平台令牌会话：按 ClipFetch 用户 + 平台标签唯一。
 * 单 token 平台（{@link #dualTokenType}=single）仅存 accessToken；
 * 双 token 平台（dual）同时存 refreshToken。账号密码不落库。
 */
@TableName("third_party_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThirdPartySession {

    /** 单令牌平台：仅有 access_token，过期需用户重新连接 */
    public static final String TYPE_SINGLE = "single";
    /** 双令牌平台：access_token + refresh_token，过期可用 refresh 自动续期 */
    public static final String TYPE_DUAL = "dual";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 平台标签，如 rag_subtitle */
    private String platform;

    /** single / dual */
    private String dualTokenType;

    private String accessToken;

    private String refreshToken;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
