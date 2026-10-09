package cc.ivera.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String nickname;

    private boolean vip;

    private LocalDateTime createdAt;
}