package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Conservative one-shot startup audit: no Worker submission or orphan reactivation. */
@Component
@RequiredArgsConstructor
public class NativeStartupRetryLeaseAudit {
    private final NativeTorrentProperties flags;
    private final NativeRetryPlanScanner scanner;
    private final NativeRunningTaskAudit runningAudit;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if(!flags.isStartupRetryLeaseAuditEnabled())return;
        if(!flags.isEnabled() || !flags.isSingleInstanceOrphanRecoveryEnabled())
            throw new IllegalStateException("Native startup audit requires enabled single-instance recovery");
        // Bounded startup sweep. A stopped expired lease is never auto-requeued.
        scanner.stopExpired(20);
        // Read-only observation; never infer orphan ownership from RUNNING alone.
        runningAudit.countObservedRunning(20);
    }
}
