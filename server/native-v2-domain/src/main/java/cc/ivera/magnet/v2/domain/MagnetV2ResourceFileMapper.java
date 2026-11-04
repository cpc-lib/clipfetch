package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MagnetV2ResourceFileMapper {

    int insertBatch(@Param("files") List<MagnetV2ResourceFile> files);

    List<MagnetV2ResourceFile> findByResource(@Param("resourceId") long resourceId);
}
