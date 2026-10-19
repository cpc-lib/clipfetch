package cc.ivera.magnet.engine.piece;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.UUID;

/**
 * Explicit recovery of a database-owned task. First fence its RUNNING revision;
 * only then reopen and rehash its persisted checkpoint. Does not dispatch I/O.
 */
@Service
@RequiredArgsConstructor
public class NativeOwnedOrphanRecoveryService {
    private final NativeWorkerJobManager workers;
    private final NativeTaskCheckpointFactory checkpoints;
    private final NativeTaskLifecycleService tasks;

    public NativeTaskLifecycleService.TaskState recover(long owner,UUID taskId,long revision)
            throws IOException {
        var fenced=workers.recoverOwnedOrphan(owner,taskId,revision);
        if(!"PAUSED".equals(fenced.state()) || fenced.revision()<=revision)
            throw new IllegalStateException("Orphan could not be safely fenced");
        var checkpoint=checkpoints.openOwned(owner,taskId);
        return tasks.reconcileStopped(owner,taskId,fenced.revision(),checkpoint);
    }
}
