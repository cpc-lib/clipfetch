package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/** Torrent info-only bencode: no uploader supplied tracker URL or credentials are shared. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("torrent_native_metadata")
public class TorrentNativeMetadata {
    @TableId(type=IdType.AUTO)
    private Long id;
    private String infoHash;
    private byte[] torrentBencode;
    private String metainfoSha256;
    private Integer pieceLength;
    private Long pieceCount;
    private Long totalBytes;
    private LocalDateTime createdAt;
}
