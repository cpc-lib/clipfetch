package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** All SQL lives in mapper/NativeDownloadRootMapper.xml. */
public interface NativeDownloadRootMapper extends BaseMapper<NativeDownloadRoot> {

    /** Owner-scoped read; never returns another user's row. */
    NativeDownloadRoot findActiveByUser(@Param("userId") long userId);

    int insertRoot(@Param("root") NativeDownloadRoot root);

    int updateActivePath(@Param("userId") long userId, @Param("rootPath") String rootPath);

    /** Logical delete writes deleted = id so repeated deletions never collide. */
    int logicalDeleteActive(@Param("userId") long userId);
}
