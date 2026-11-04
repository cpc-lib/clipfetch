package cc.ivera.magnet.v2.domain;

import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MagnetV2ParseJobMapper {

    int insert(MagnetV2ParseJob job);

    Optional<MagnetV2ParseJob> findByUuid(@Param("parseUuid") String parseUuid);

    Optional<MagnetV2ParseJob> findOwned(@Param("parseUuid") String parseUuid,
                                         @Param("userId") long userId);

    List<MagnetV2ParseJob> listOwned(@Param("userId") long userId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    List<MagnetV2ParseJob> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claimPending(@Param("id") long id, @Param("expectedRevision") long expectedRevision);

    int markReady(@Param("id") long id,
                  @Param("userId") long userId,
                  @Param("expectedRevision") long expectedRevision,
                  @Param("resourceId") long resourceId);

    int markFailed(@Param("id") long id,
                   @Param("userId") long userId,
                   @Param("expectedRevision") long expectedRevision,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    int logicalDelete(@Param("id") long id, @Param("userId") long userId);
}
