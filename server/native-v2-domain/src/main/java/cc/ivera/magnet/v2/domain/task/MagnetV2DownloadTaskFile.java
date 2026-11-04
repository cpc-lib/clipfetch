package cc.ivera.magnet.v2.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("magnet_v2_download_task_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MagnetV2DownloadTaskFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private Integer fileIndex;
    private Boolean selected;
    private Long declaredBytes;
    private Long verifiedBytes;
    private String state;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Long deleted;
}
