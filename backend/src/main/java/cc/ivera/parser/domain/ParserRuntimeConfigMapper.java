package cc.ivera.parser.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface ParserRuntimeConfigMapper extends BaseMapper<ParserRuntimeConfig> {

    ParserRuntimeConfig findByKey(@Param("configKey") String configKey);

    int insertConfig(@Param("c") ParserRuntimeConfig config);

    int updateConfig(@Param("c") ParserRuntimeConfig config);

    int logicalDeleteById(@Param("id") Long id);
}
