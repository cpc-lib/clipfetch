package cc.ivera.magnet.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MagnetHistoryMapper extends BaseMapper<MagnetHistory> {

    /**
     * 逻辑删除：deleted 设为记录 id（而非固定 1），
     * 避免 MySQL 唯一索引 (user_id, info_hash, deleted) 在多条同 Hash 删除时冲突。
     * SQL 见 resources/mapper/MagnetHistoryMapper.xml。
     */
    int logicalDeleteById(@Param("id") Long id, @Param("userId") Long userId);
}
