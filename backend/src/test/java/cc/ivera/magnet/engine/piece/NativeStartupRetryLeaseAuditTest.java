package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeStartupRetryLeaseAuditTest {
    @Test void defaultsDoNotTouchTheDatabase() {
        var scanner=mock(NativeRetryPlanScanner.class);
        new NativeStartupRetryLeaseAudit(new NativeTorrentProperties(),scanner,mock(NativeRunningTaskAudit.class)).onReady();
        verifyNoInteractions(scanner);
    }

    @Test void startupAuditRequiresExplicitSingleInstanceOwnership() {
        var props=new NativeTorrentProperties();
        props.setStartupRetryLeaseAuditEnabled(true);
        var scanner=mock(NativeRetryPlanScanner.class);
        assertThrows(IllegalStateException.class,
                ()->new NativeStartupRetryLeaseAudit(props,scanner,mock(NativeRunningTaskAudit.class)).onReady());
        props.setEnabled(true);
        assertThrows(IllegalStateException.class,
                ()->new NativeStartupRetryLeaseAudit(props,scanner,mock(NativeRunningTaskAudit.class)).onReady());
        verifyNoInteractions(scanner);
    }

    @Test void enabledAuditOnlyStopsExpiredLeasesOnce() {
        var props=new NativeTorrentProperties();
        props.setEnabled(true);
        props.setSingleInstanceOrphanRecoveryEnabled(true);
        props.setStartupRetryLeaseAuditEnabled(true);
        var scanner=mock(NativeRetryPlanScanner.class);
        new NativeStartupRetryLeaseAudit(props,scanner,mock(NativeRunningTaskAudit.class)).onReady();
        verify(scanner,times(1)).stopExpired(20);
        verifyNoMoreInteractions(scanner);
    }
}
