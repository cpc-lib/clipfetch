package cc.ivera.magnet.engine.piece;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.UUID;

/**
 * Explicit two-phase recovery. The operator must separately confirm single-JVM
 * ownership before enabling the existing orphan recovery flag.
 * Never dispatches a network job or automatically resumes a task.
 */
@Service
@RequiredArgsConstructor
public class NativeOrphanReconciliationService {
    private final NativeWorkerJobManager jobs;
    private final NativeTaskLifecycleService tasks;

    public record Recovery(NativeTaskLifecycleService.TaskState fenced,
                           NativeTaskLifecycleService.TaskState reconciled) {}

    public Recovery recoverAndReverify(long owner, UUID taskId, long revision,
                                      VerifiedPieceCheckpoint checkpoint) throws IOException {
        if(checkpoint==null)throw new IllegalArgumentException("Missing verified checkpoint");
        // The persistent RUNNING revision is fenced before ANY disk reconciliation.
        var fenced=jobs.recoverOwnedOrphan(owner,taskId,revision);
        if(!"PAUSED".equals(fenced.state()) || fenced.revision()<=revision)
            throw new IllegalStateException("Could not fence orphaned task");
        // The lifecycle layer rehashes owned on-disk Pieces and validates selection.
        var reconciled=tasks.reconcileStopped(owner,taskId,fenced.revision(),checkpoint);
        if(!"PAUSED".equals(reconciled.state()) && !"COMPLETED".equals(reconciled.state()))
            throw new IllegalStateException("Unexpected reconciled task state");
        return new Recovery(fenced,reconciled);
    }
}
