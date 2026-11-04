package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.V2ConflictException;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.domain.MagnetV2ResourceFileMapper;
import cc.ivera.magnet.v2.domain.MagnetV2ResourceHashAliasMapper;
import cc.ivera.magnet.v2.domain.MagnetV2ResourceMapper;
import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceHashAlias;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MagnetV2ResourceRepositoryAdapter implements NativeV2ResourceRepository {

    private final MagnetV2ResourceMapper resourceMapper;
    private final MagnetV2ResourceHashAliasMapper aliasMapper;
    private final MagnetV2ResourceFileMapper fileMapper;

    public MagnetV2ResourceRepositoryAdapter(MagnetV2ResourceMapper resourceMapper,
                                             MagnetV2ResourceHashAliasMapper aliasMapper,
                                             MagnetV2ResourceFileMapper fileMapper) {
        this.resourceMapper = resourceMapper;
        this.aliasMapper = aliasMapper;
        this.fileMapper = fileMapper;
    }

    @Override
    public Optional<MagnetV2Resource> findReadyByAlias(String hashType, byte[] hash) {
        return resourceMapper.findReadyByAlias(hashType, hash);
    }

    @Override
    public Optional<MagnetV2Resource> findById(long id) {
        return resourceMapper.findById(id);
    }

    @Override
    public MagnetV2Resource insert(MagnetV2Resource resource) {
        try {
            resourceMapper.insert(resource);
            return resource;
        } catch (DuplicateKeyException error) {
            throw new V2ConflictException("资源规范化键已被占用");
        }
    }

    @Override
    public void insertAliases(List<MagnetV2ResourceHashAlias> values) {
        for (MagnetV2ResourceHashAlias alias : values) {
            try {
                aliasMapper.insert(alias);
            } catch (DuplicateKeyException error) {
                throw new V2ConflictException("哈希别名已被占用");
            }
        }
    }

    @Override
    public void insertFiles(List<MagnetV2ResourceFile> values) {
        if (!values.isEmpty()) {
            fileMapper.insertBatch(values);
        }
    }

    @Override
    public List<MagnetV2ResourceFile> findFiles(long resourceId) {
        return fileMapper.findByResource(resourceId);
    }
}
