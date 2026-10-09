package cc.ivera.temp.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TempLinkMapper extends BaseMapper<TempLink> {

    /**
     * 按 url 统计物理行数（绕过 @TableLogic，含已逻辑删除的记录）。
     * temp.url 有物理唯一索引，重新导入时需据此判断是新增还是恢复。
     */
    @Select("SELECT COUNT(*) FROM temp WHERE url = #{url}")
    long countPhysicalByUrl(@Param("url") String url);

    /**
     * 恢复/刷新已存在的记录：撤销逻辑删除并更新付费类型（vip 为 null 时保留原值）。
     * downloaded、source_url、created_at 等历史信息保留不动。
     */
    @Update("UPDATE temp SET deleted = 0, vip = COALESCE(#{vip}, vip) WHERE url = #{url}")
    int undeleteByUrl(@Param("url") String url, @Param("vip") Integer vip);
}
