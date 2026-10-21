package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerHealthTrackerTest {
    @Test
    void partialSessionDoesNotPromotePeer() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);

        health.observeSession(peer, 16 * 1024, Duration.ofSeconds(1).toNanos(), 0);
        assertEquals(0, health.verifiedPeerCount());

        health.observeSession(peer, 16 * 1024, Duration.ofSeconds(1).toNanos(), 1);
        assertEquals(1, health.verifiedPeerCount());
    }

    @Test
    void degradedVerifiedPeerDoesNotIncreaseActorTarget() {
        var healthy = new InetSocketAddress("192.0.2.10", 6881);
        var degraded = new InetSocketAddress("192.0.2.11", 6881);
        var health = new PeerHealthTracker(List.of(healthy, degraded), () -> 0L, 8_000);

        health.success(healthy);
        health.success(degraded);
        health.failure(degraded);
        health.failure(degraded);

        assertEquals(5, PeerMultiplexedRound.computeTargetActors(health, 4, 64));
    }

    @Test
    void oneFailedVerifiedPeerDoesNotIncreaseActorTarget() {
        var healthy = new InetSocketAddress("192.0.2.10", 6881);
        var failed = new InetSocketAddress("192.0.2.11", 6881);
        var other = new InetSocketAddress("192.0.2.12", 6881);
        var health = new PeerHealthTracker(List.of(healthy, failed, other), () -> 0L, 8_000);

        health.success(healthy);
        health.success(failed);
        health.success(other);
        health.failure(failed);

        assertEquals(6, PeerMultiplexedRound.computeTargetActors(health, 4, 64));
    }

    @Test
    void failedPeerIsSkippedWhenAlternativeExists() {
        var failed = new InetSocketAddress("192.0.2.10", 6881);
        var healthy = new InetSocketAddress("192.0.2.11", 6881);
        var availability = new PeerPieceAvailability(1, Map.of());
        var health = new PeerHealthTracker(List.of(failed, healthy), () -> 0L, 8_000);

        health.failure(failed);

        var leased = health.leaseExclusive(Set.of(0), availability, Set.of(failed));

        assertEquals(healthy, leased);
        health.release(leased);
    }

    @Test
    void failedPeerIsUsedOnlyAsFallbackAfterCooldownWhenNoAlternativeExists() {
        var failed = new InetSocketAddress("192.0.2.10", 6881);
        var now = new AtomicLong();
        var availability = new PeerPieceAvailability(1, Map.of());
        var health = new PeerHealthTracker(List.of(failed), now::get, 8_000);

        health.failure(failed);

        assertNull(health.leaseExclusive(Set.of(0), availability, Set.of(failed)));
        now.set(Duration.ofMillis(250).toNanos());
        assertEquals(failed, health.leaseExclusive(Set.of(0), availability, Set.of(failed)));
        health.release(failed);
    }

    @Test
    void failedPeerFallbackScansEachPeerOnce() {
        var healthy = new InetSocketAddress("192.0.2.10", 6881);
        var failed = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var availability = new PeerPieceAvailability(1, Map.of(healthy, new java.util.BitSet()));
        var health = new PeerHealthTracker(List.of(healthy, failed), now::get, 8_000);
        health.failure(failed);
        now.set(Duration.ofMillis(250).toNanos());

        var pieceIterations = new AtomicInteger();
        Set<Integer> pieces = new AbstractSet<>() {
            @Override
            public Iterator<Integer> iterator() {
                pieceIterations.incrementAndGet();
                return Set.of(0).iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        assertEquals(failed, health.leaseExclusive(pieces, availability, Set.of(failed)));
        assertEquals(3, pieceIterations.get());
        health.release(failed);
    }

    @Test
    void combinedLeaseScanDoesNotRepeatAvailabilityTraversalForDelay() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var availability = new PeerPieceAvailability(1, Map.of(
                first, new java.util.BitSet(),
                second, new java.util.BitSet()));
        var health = new PeerHealthTracker(List.of(first, second), () -> 0L, 8_000);
        var pieceIterations = new AtomicInteger();
        Set<Integer> pieces = new AbstractSet<>() {
            @Override
            public Iterator<Integer> iterator() {
                pieceIterations.incrementAndGet();
                return Set.of(0).iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        var result = health.scanLease(pieces, availability, Set.of());

        assertNull(result.peer());
        assertEquals(Long.MAX_VALUE, result.nextLeaseDelayNanos());
        assertEquals(3, pieceIterations.get());
    }

    @Test
    void combinedLeaseScanUsesEarliestIdleServingPeerCooldown() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var health = new PeerHealthTracker(List.of(first, second), now::get, 8_000);
        health.failure(first);
        health.failure(second);
        health.failure(second);

        var result = health.scanLease(Set.of(0),
                new PeerPieceAvailability(1, Map.of()), Set.of(first, second));

        assertNull(result.peer());
        assertEquals(Duration.ofMillis(250).toNanos(),
                result.nextLeaseDelayNanos());
    }

    @Test
    void confirmedPeerStillOutranksUnknownPeer() {
        var unknown = new InetSocketAddress("192.0.2.10", 6881);
        var confirmed = new InetSocketAddress("192.0.2.11", 6881);
        var bits = new java.util.BitSet();
        bits.set(1);
        var availability = new PeerPieceAvailability(2, Map.of(confirmed, bits));
        var health = new PeerHealthTracker(List.of(unknown, confirmed), () -> 0L, 8_000);

        assertEquals(confirmed,
                health.leaseExclusive(Set.of(0, 1), availability));
        health.release(confirmed);
    }

    @Test
    void leaseWaitUsesTheEarlierCooldownOrDeadline() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.MILLISECONDS.toNanos(300), TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(TimeUnit.SECONDS.toNanos(3),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), Long.MAX_VALUE));
    }

    @Test
    void leaseWaitUsesOneMillisecondProgressFloorForExpiredCooldown() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), 0));
        assertEquals(0,
                PeerHealthTracker.boundedLeaseWaitNanos(0, TimeUnit.MILLISECONDS.toNanos(500)));
    }

    @Test
    void nextLeaseDelayUsesTheEarliestIdleServingPeer() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var health = new PeerHealthTracker(List.of(first, second), now::get, 8_000);
        health.failure(first);
        health.failure(second);
        health.failure(second);
        var availability = new PeerPieceAvailability(1, Map.of());

        assertEquals(TimeUnit.MILLISECONDS.toNanos(250),
                health.nextLeaseDelayNanos(Set.of(0), availability));
    }

    @Test
    void nextLeaseDelayReportsNoFiniteDelayWhenServingPeerIsBusy() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
        var availability = new PeerPieceAvailability(1, Map.of());

        assertEquals(peer, health.leaseExclusive(Set.of(0), availability));
        assertEquals(Long.MAX_VALUE,
                health.nextLeaseDelayNanos(Set.of(0), availability));
        health.release(peer);
    }

    @Test
    void nextLeaseDelayReportsNoFiniteDelayWhenPeerCannotServePiece() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
        var availability = new PeerPieceAvailability(1,
                Map.of(peer, new java.util.BitSet()));

        assertEquals(Long.MAX_VALUE,
                health.nextLeaseDelayNanos(Set.of(0), availability));
    }

    @Test
    void awaitLeaseUsesFiniteCooldownDelayBeforeWaiting() throws Exception {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var clockCalls = new AtomicInteger();
        var health = new PeerHealthTracker(List.of(peer), () -> {
            clockCalls.incrementAndGet();
            return 0L;
        });
        health.failure(peer);
        var availability = new PeerPieceAvailability(1, Map.of());

        assertNull(health.awaitLease(
                Set.of(0), availability, Set.of(peer), Duration.ofMillis(100).toNanos()));
        assertTrue(clockCalls.get()>=3);
    }

    @Test
    void awaitLeaseWithStatusReportsImmediateLeaseWithoutMiss() throws Exception {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var availability = new PeerPieceAvailability(1, Map.of());
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);

        var result = health.awaitLeaseWithStatus(
                Set.of(0), availability, Set.of(), Duration.ofMillis(100).toNanos());

        assertEquals(peer, result.peer());
        assertFalse(result.missedInitialLease());
        health.release(peer);
    }

    @Test
    void existingExclusiveLeaseOverloadStillSelectsAvailablePeer() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var availability = new PeerPieceAvailability(1, Map.of());
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);

        assertEquals(peer, health.leaseExclusive(Set.of(0), availability));
        health.release(peer);
    }

    @Test
    void waitingLeaseWakesWhenHeldPeerIsReleased() throws Exception {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var availability = new PeerPieceAvailability(1, Map.of());
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
        assertEquals(peer, health.leaseExclusive(Set.of(0), availability));

        var started = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var leasedPeer = new AtomicReference<PeerHealthTracker.LeaseWaitResult>();
        var failure = new AtomicReference<Throwable>();
        var waiter = new Thread(() -> {
            started.countDown();
            try {
                leasedPeer.set(health.awaitLeaseWithStatus(
                        Set.of(0), availability, Set.of(), Duration.ofMillis(250).toNanos()));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                completed.countDown();
            }
        }, "peer-health-waiter");

        waiter.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(40);
        health.release(peer);

        try {
            assertTrue(completed.await(200, TimeUnit.MILLISECONDS));
        } finally {
            waiter.interrupt();
            waiter.join(1_000);
        }

        assertNull(failure.get());
        assertTrue(leasedPeer.get().missedInitialLease());
        assertEquals(peer, leasedPeer.get().peer());
        health.release(leasedPeer.get().peer());
    }

    @Test
    void nativePropertiesDefaultToThirtyTwoExplorationSlots() {
        assertEquals(32,
                new cc.ivera.magnet.engine.config.NativeTorrentProperties()
                        .getPeerExplorationSlots());
    }

    @Test
    void nativePropertiesAllowAConfiguredExplorationWindow() {
        var properties = new cc.ivera.magnet.engine.config.NativeTorrentProperties();
        properties.setPeerExplorationSlots(2);

        assertEquals(2, properties.getPeerExplorationSlots());
    }

}
