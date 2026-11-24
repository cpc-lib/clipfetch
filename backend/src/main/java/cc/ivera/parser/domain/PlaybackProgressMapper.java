package cc.ivera.parser.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface PlaybackProgressMapper extends BaseMapper<PlaybackProgress> {

    PlaybackProgress findOwned(@Param("userId") Long userId,
                               @Param("sourceKey") String sourceKey);

    int insertProgress(@Param("p") PlaybackProgress progress);

    int updateProgress(@Param("p") PlaybackProgress progress);
}
