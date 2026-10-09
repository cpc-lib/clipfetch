package cc.ivera.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String token;

    private LocalDateTime expiresAt;

    private boolean revoked;

    private LocalDateTime createdAt;
}