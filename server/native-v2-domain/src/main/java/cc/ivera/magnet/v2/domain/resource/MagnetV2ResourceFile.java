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

@TableName("magnet_v2_resource_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2ResourceFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resourceId;
    private Integer fileIndex;
    private String relativePath;
    private Long declaredBytes;
    private byte[] piecesRoot;
    private LocalDateTime createdAt;
}
