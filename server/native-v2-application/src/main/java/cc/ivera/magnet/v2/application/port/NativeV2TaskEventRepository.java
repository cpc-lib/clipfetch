package cc.ivera.magnet.v2.application.port;

import cc.ivera.magnet.v2.domain.task.MagnetV2TaskEvent;

import java.util.List;

public interface NativeV2TaskEventRepository {

    void append(long userId, long taskId, String eventType, String payloadJson);

    List<MagnetV2TaskEvent> listRecent(long taskId, int limit);

    int deleteByTask(long taskId);
}
