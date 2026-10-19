package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Observation only. RUNNING tasks may be legitimately owned by another JVM. */
@Service
@RequiredArgsConstructor
public class NativeRunningTaskAudit {
    private final TorrentNativeTaskMapper tasks;

    /** Bounded aggregate only: no user identifiers or task locations leave this API. */
    public int countObservedRunning(int limit) {
        if(limit < 1 || limit > 20)
            throw new IllegalArgumentException("Invalid running audit limit");
        return tasks.listRunningForAudit(limit).size();
    }
}
