package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

public interface MagnetV2ResourceMapper {

    int insert(MagnetV2Resource resource);

    Optional<MagnetV2Resource> findById(@Param("id") long id);

    Optional<MagnetV2Resource> findReadyByAlias(@Param("hashType") String hashType,
                                                @Param("hashBytes") byte[] hashBytes);
}
