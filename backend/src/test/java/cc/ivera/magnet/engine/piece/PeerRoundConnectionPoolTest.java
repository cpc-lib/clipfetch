package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRoundConnectionPoolTest {
    @Test
    void queuedInputKeepsIdlePeerAliveWhenCachedAvailabilityHasNoWork() {
        assertFalse(PeerRoundConnectionPool.shouldExitIdleMailbox(
                Duration.ofSeconds(4).toNanos(),false,true,false));
    }

    @Test
    void partialInputKeepsIdlePeerAliveWhenCachedAvailabilityHasNoWork() {
        assertFalse(PeerRoundConnectionPool.shouldExitIdleMailbox(
                Duration.ofSeconds(4).toNanos(),false,false,true));
    }

    @Test
    void idlePeerExitsOnlyWhenNoWorkOrInputRemain() {
        assertTrue(PeerRoundConnectionPool.shouldExitIdleMailbox(
                Duration.ofSeconds(4).toNanos(),false,false,false));
    }

    @Test
    void unknownPeerUsesConfiguredFirstBlockTimeout() {
        assertEquals(Duration.ofSeconds(12).toNanos(),
                PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                        12_000,36_000,0));
    }

    @Test
    void knownPeerUsesRttP95WithFloorAndGlobalCap() {
        assertEquals(Duration.ofSeconds(12).toNanos(),
                PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                        12_000,36_000,Duration.ofSeconds(2).toNanos()));
        assertEquals(Duration.ofSeconds(32).toNanos(),
                PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                        12_000,36_000,Duration.ofSeconds(8).toNanos()));
        assertEquals(Duration.ofSeconds(36).toNanos(),
                PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                        12_000,36_000,Duration.ofSeconds(20).toNanos()));
    }

    @Test
    void firstBlockTimeoutCannotExceedGlobalStallTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                        36_001,36_000,0));
    }

    @Test
    void readWaitUsesTheEarlierPeerProgressDeadline() {
        assertEquals(3_000,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(5).toNanos(),
                        Duration.ofSeconds(3).toNanos()));
    }

    @Test
    void readWaitUsesTheEarlierTaskDeadline() {
        assertEquals(2_000,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(2).toNanos(),
                        Duration.ofSeconds(3).toNanos()));
    }

    @Test
    void readWaitKeepsExpiredOrSubMillisecondWindowProgressing() {
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        500_000L,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        0,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        -1,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        1_000_000L,Duration.ofSeconds(1).toNanos()));
        assertEquals(2,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        1_000_001L,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(1).toNanos(),
                        Duration.ofSeconds(1).toNanos(),0));
    }

    @Test
    void readWaitCapsAtIntegerMaxMillisWithoutOverflow() {
        assertEquals(Integer.MAX_VALUE,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Long.MAX_VALUE,Long.MAX_VALUE));
    }

    @Test
    void readWaitUsesTheExistingMailboxCheckWindow() {
        assertEquals(250,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(5).toNanos(),
                        Duration.ofSeconds(3).toNanos(),
                        Duration.ofMillis(250).toNanos()));
    }

    @Test
    void mailboxReadWindowUsesRemainingCheckTime() {
        long now=Duration.ofSeconds(1).toNanos();
        assertEquals(Duration.ofMillis(50).toNanos(),
                PeerRoundConnectionPool.remainingMailboxCheckNanos(
                        now+Duration.ofMillis(50).toNanos(),now,
                        Duration.ofMillis(250).toNanos()));
        assertEquals(-1,
                PeerRoundConnectionPool.remainingMailboxCheckNanos(
                        now-1,now,Duration.ofMillis(250).toNanos()));
        assertEquals(Duration.ofMillis(250).toNanos(),
                PeerRoundConnectionPool.remainingMailboxCheckNanos(
                        0,now,Duration.ofMillis(250).toNanos()));
    }

    @Test
    void expiredMailboxCheckFallsBackOnlyWhenNoSlotsAreAvailable() {
        assertEquals(Duration.ofMillis(250).toNanos(),
                PeerRoundConnectionPool.boundedMailboxCheckNanos(
                        -1,0,Duration.ofMillis(250).toNanos()));
        assertEquals(-1,
                PeerRoundConnectionPool.boundedMailboxCheckNanos(
                        -1,1,Duration.ofMillis(250).toNanos()));
    }
}
