package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeRetryPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Explicit operator-invoked bridge; no @Scheduled and no public HTTP endpoint.
 * A claimed plan alone never implies permission to restart a paused task.
 */
@Service
@RequiredArgsConstructor
public class NativeRetryWorkerBinder {
    private final TorrentNativeRetryPlanMapper plans;
    private final NativeTaskLifecycleService tasks;
    private final NativeWorkerJobManager workers;

    /** Persist only an actual terminal Job status; no implicit network retry. */
    public boolean recordTerminal(NativeRetryPlanScanner.Claimed claim,UUID jobId) {
        if(claim==null || jobId==null)throw new IllegalArgumentException("Missing job binding");
        var plan=plans.findOwned(claim.owner(),claim.taskUuid().toString());
        if(plan==null || !"CLAIMED".equals(plan.getStatus()) ||
                !claim.leaseToken().toString().equals(plan.getLeaseToken()) ||
                !jobId.toString().equals(plan.getWorkerJobUuid()) ||
                plan.getExpectedRevision()!=claim.revision())
            return false;
        var snapshot=workers.status(claim.owner(),jobId);
        if(!snapshot.taskUuid().equals(claim.taskUuid()) || !snapshot.terminal())return false;
        return plans.finishWorker(claim.owner(),claim.taskUuid().toString(),claim.revision(),
                claim.leaseToken().toString(),jobId.toString(),snapshot.state().name(),
                LocalDateTime.now(ZoneOffset.UTC))==1;
    }

    public UUID submitClaim(NativeRetryPlanScanner.Claimed claim) {
        if(claim==null)throw new IllegalArgumentException("Missing retry claim");
        var plan=plans.findOwned(claim.owner(),claim.taskUuid().toString());
        if(plan==null || !"CLAIMED".equals(plan.getStatus()) ||
                !claim.leaseToken().toString().equals(plan.getLeaseToken()) ||
                plan.getExpectedRevision()!=claim.revision() ||
                plan.getWorkerJobUuid()!=null ||
                plan.getLeaseUntil()==null ||
                !plan.getLeaseUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw new IllegalStateException("Retry claim is no longer valid");
        var state=tasks.get(claim.owner(),claim.taskUuid());
        if(!"READY".equals(state.state()) || state.revision()!=claim.revision())
            throw new IllegalStateException("Native task is not at the claimed READY revision");
        UUID job=workers.submit(claim.owner(),claim.taskUuid(),claim.revision(),1);
        try {
            if(plans.bindWorker(claim.owner(),claim.taskUuid().toString(),claim.revision(),
                    claim.leaseToken().toString(),job.toString(),LocalDateTime.now(ZoneOffset.UTC))!=1)
                throw new IllegalStateException("Worker binding was lost");
        }catch(RuntimeException failure) {
            try {workers.cancel(claim.owner(),job);}catch(RuntimeException ignored) {}
            throw failure;
        }
        return job;
    }
}
