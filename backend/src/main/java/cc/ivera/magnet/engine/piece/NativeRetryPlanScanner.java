package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeRetryPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Explicit, bounded plan claim primitive. Does not schedule itself or start network work.
 * A CLAIМED record must be reconciled by a future fenced executor before dispatch.
 */
@Service
@RequiredArgsConstructor
public class NativeRetryPlanScanner {
    private final TorrentNativeRetryPlanMapper plans;

    public record Claimed(long owner,UUID taskUuid,long revision,UUID leaseToken) {}

    /**
     * Explicit crash audit: stop expired leases, never requeue them automatically.
     * Returning rows is intentionally avoided to prevent leaking owner/task identifiers.
     */
    public int stopExpired(int limit) {
        if(limit<1 || limit>20)throw new IllegalArgumentException("Invalid expired scan limit");
        var now=LocalDateTime.now(ZoneOffset.UTC);
        int stopped=0;
        for(var plan:plans.listExpiredClaims(now,limit)) {
            stopped+=plans.expireClaim(plan.getUserId(),plan.getTaskUuid(),
                    plan.getExpectedRevision(),now);
        }
        return stopped;
    }

    public List<Claimed> claimDue(int limit,Duration lease) {
        if(limit<1 || limit>20 || lease==null || lease.compareTo(Duration.ofSeconds(10))<0 ||
                lease.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Invalid bounded scan parameters");
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        var due=plans.listDue(now,limit);
        var claimed=new java.util.ArrayList<Claimed>();
        for(var plan:due) {
            UUID token=UUID.randomUUID();
            if(plans.claimDue(plan.getUserId(),plan.getTaskUuid(),plan.getExpectedRevision(),
                    token.toString(),now,now.plus(lease))==1)
                claimed.add(new Claimed(plan.getUserId(),UUID.fromString(plan.getTaskUuid()),
                        plan.getExpectedRevision(),token));
        }
        return List.copyOf(claimed);
    }
}
