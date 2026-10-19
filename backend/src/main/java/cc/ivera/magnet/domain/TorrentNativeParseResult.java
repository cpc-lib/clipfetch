package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import java.time.LocalDateTime;

/** User-owned, successfully verified Metadata summary; independent from qBittorrent history. */
@TableName("torrent_native_parse_result")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TorrentNativeParseResult {
    @TableId(type=IdType.AUTO)
    private Long id;
    private String jobUuid;
    private Long userId;
    private String infoHash;
    private String displayName;
    private Long totalBytes;
    @JsonIgnore private String filesJson;
    @JsonIgnore private byte[] verifiedTorrentBytes;
    private LocalDateTime createdAt;
    @TableLogic private Long deleted;
}
