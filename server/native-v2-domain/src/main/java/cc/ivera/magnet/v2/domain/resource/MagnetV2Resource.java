package cc.ivera.magnet.v2.domain.resource;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("magnet_v2_resource")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2Resource {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceUuid;
    private String canonicalKey;
    private byte[] btih;
    private byte[] btmh;
    private VersionType versionType;
    private String name;
    private Long pieceLength;
    private Long totalBytes;
    private Integer fileCount;
    private Boolean privateFlag;
    private String metadataKey;
    private String metadataStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
