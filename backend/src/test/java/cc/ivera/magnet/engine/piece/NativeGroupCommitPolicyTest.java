package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeGroupCommitPolicyTest {
    @Test void publishesAtConfiguredCountOrBytesOrDeadlineOrFinalPiece() {
        long start=TimeUnit.SECONDS.toNanos(5);
        assertFalse(NativeGroupCommitPolicy.shouldPublish(0,64,0,start,start+4_000_000_000L,true));
        assertFalse(NativeGroupCommitPolicy.shouldPublish(4,64,1024,start,start+1_000_000_000L,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(64,64,1024,start,start,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(4,64,16L*1024*1024,start,start,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(4,64,1024,start,start+2_000_000_000L,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(1,64,1024,start,start,true));
        assertFalse(NativeGroupCommitPolicy.shouldPublish(64,256,16L*1024*1024,start,start+1_000_000_000L,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(256,256,1024,start,start,false));
        assertTrue(NativeGroupCommitPolicy.shouldPublish(4,256,64L*1024*1024,start,start,false));
        assertEquals(1000,NativeGroupCommitPolicy.maxPollMillis(0,0,start));
        assertEquals(200,NativeGroupCommitPolicy.maxPollMillis(1,start,start));
        assertEquals(1,NativeGroupCommitPolicy.maxPollMillis(1,start,
                start+NativeGroupCommitPolicy.MAX_UNPUBLISHED_NANOS));
    }
    @Test void invalidGroupBudgetCannotTriggerCommit() {
        assertThrows(IllegalArgumentException.class,
                ()->NativeGroupCommitPolicy.shouldPublish(-1,64,1,1,2,false));
        assertThrows(IllegalArgumentException.class,
                ()->NativeGroupCommitPolicy.shouldPublish(1,0,1,1,2,false));
    }
}
