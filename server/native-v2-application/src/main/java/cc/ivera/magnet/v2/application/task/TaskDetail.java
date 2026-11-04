package cc.ivera.magnet.v2.application.task;

import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTask;
import cc.ivera.magnet.v2.domain.task.MagnetV2DownloadTaskFile;
import cc.ivera.magnet.v2.domain.task.MagnetV2TaskEvent;

import java.util.List;

public record TaskDetail(MagnetV2DownloadTask task,
                         List<MagnetV2DownloadTaskFile> files,
                         List<MagnetV2TaskEvent> events) {
}
