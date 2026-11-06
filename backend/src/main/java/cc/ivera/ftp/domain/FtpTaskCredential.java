package cc.ivera.ftp.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ftp_task_credential")
public class FtpTaskCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long userId;
    private String authMode;
    @JsonIgnore
    private String encryptedUsername;
    @JsonIgnore
    private String encryptedPassword;
    @JsonIgnore
    private String usernameNonce;
    @JsonIgnore
    private String passwordNonce;
    @JsonIgnore
    private String keyId;
    @JsonIgnore
    private Integer cipherVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
