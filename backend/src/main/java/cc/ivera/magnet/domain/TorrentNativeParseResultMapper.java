package cc.ivera.magnet.domain;

import org.apache.ibatis.annotations.Param;

/** All persisted metadata result SQL is in resources/mapper XML. */
public interface TorrentNativeParseResultMapper {
    int insertSuccess(@Param("record") TorrentNativeParseResult record);
    TorrentNativeParseResult findOwned(@Param("userId") long userId,
                                      @Param("jobUuid") String jobUuid);
}
