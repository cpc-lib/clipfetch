package cc.ivera.magnet.v2.domain;

import org.apache.ibatis.annotations.Param;

public interface MagnetV2TaskEventMapper {

    int insert(@Param("userId") long userId,
               @Param("taskId") long taskId,
               @Param("eventType") String eventType,
               @Param("payloadJson") String payloadJson);

    java.util.List<cc.ivera.magnet.v2.domain.task.MagnetV2TaskEvent> listRecent(
            @Param("taskId") long taskId, @Param("limit") int limit);
}
