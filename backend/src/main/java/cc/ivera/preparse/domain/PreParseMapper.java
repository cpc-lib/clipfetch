package cc.ivera.preparse.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PreParseMapper extends BaseMapper<PreParse> {

    /**
     * 逻辑删除：deleted 设为记录 id（而非固定 1），
     * 避免 MySQL 唯一索引 (user_id, url, deleted) 在多条同 URL 删除时冲突。
     */
    @Update("UPDATE pre_parse SET deleted = id WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int logicalDeleteById(@Param("id") Long id, @Param("userId") Long userId);
}
