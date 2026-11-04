package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceHashAlias;

import java.util.List;
import java.util.Optional;

/** 公共资源仓储；别名唯一约束由实现层映射为 CONFLICT。 */
public interface NativeV2ResourceRepository {

    Optional<MagnetV2Resource> findReadyByAlias(String hashType, byte[] hash);

    Optional<MagnetV2Resource> findById(long id);

    MagnetV2Resource insert(MagnetV2Resource resource);

    void insertAliases(List<MagnetV2ResourceHashAlias> aliases);

    void insertFiles(List<MagnetV2ResourceFile> files);

    List<MagnetV2ResourceFile> findFiles(long resourceId);
}
