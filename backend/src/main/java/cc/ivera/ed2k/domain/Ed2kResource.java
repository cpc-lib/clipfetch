package cc.ivera.ed2k.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("ed2k_resource")
public class Ed2kResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String md4Hash;
    private Long fileSize;
    private String internalName;
    private String engineState;
    private Boolean engineManaged;
    private String engineRef;
    private String completedPath;
    private LocalDateTime completedVerifiedAt;
    private Long observedBytes;
    private Long speedBps;
    private Integer sourcesTotal;
    private Integer sourcesTransferring;
    private LocalDateTime lastSnapshotAt;
    private String errorCode;
    private String errorMessage;
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
