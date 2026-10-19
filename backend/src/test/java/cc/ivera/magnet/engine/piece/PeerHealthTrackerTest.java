package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PeerHealthTrackerTest {
    private static InetSocketAddress peer(String ip) {
        return InetSocketAddress.createUnresolved(ip,6881);
    }
    @Test void newRoundRetainsPenaltyOnlyForMatchingEndpoints() {
        var bad=peer("8.8.8.8");
        var good=peer("9.9.9.9");
        var prior=new PeerHealthTracker(List.of(bad,good));
        prior.failure(bad);
        var carried=new PeerHealthTracker(List.of(bad,good),prior);
        assertEquals(good,carried.select(0,Set.of()));
        var otherTask=new PeerHealthTracker(List.of(bad,good),(PeerHealthTracker)null);
        assertEquals(bad,otherTask.select(0,Set.of()),
                "Independent task must not inherit another task's penalty");
        var changedSet=new PeerHealthTracker(List.of(good,peer("1.1.1.1")),prior);
        assertNotNull(changedSet.select(0,Set.of()));
    }

    @Test void parallelLeasesSpreadWorkAndReleaseAfterCompletion() {
        var a=peer("8.8.8.8");
        var b=peer("9.9.9.9");
        var health=new PeerHealthTracker(List.of(a,b));
        assertEquals(a,health.lease(0,Set.of(),null));
        assertEquals(b,health.lease(0,Set.of(),null),
                "Two parallel workers should prefer separate Peers");
        health.release(a);
        assertEquals(a,health.lease(0,Set.of(),null),
                "Releasing a Peer must make it available to the next Piece");
        health.release(a);
        health.release(b);
        health.failure(a);
        assertEquals(b,health.lease(0,Set.of(),null),
                "Failed Peers remain in cooldown even under parallel load");
        health.release(b);
    }

    @Test void respectsAvailablePiecesAndCoolingPeersWhenAllocatingConcurrency() {
        var a=peer("8.8.8.8");
        var b=peer("9.9.9.9");
        var c=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(a,b,c));
        var reports=new java.util.LinkedHashMap<java.net.InetSocketAddress,java.util.BitSet>();
        reports.put(a,java.util.BitSet.valueOf(new long[]{1L}));
        reports.put(b,new java.util.BitSet());
        reports.put(c,java.util.BitSet.valueOf(new long[]{2L}));
        var availability=new PeerPieceAvailability(2,reports);
        assertEquals(2,tracker.eligibleCount(List.of(0,1),availability));
        assertEquals(1,tracker.adaptiveSlots(64,64,List.of(0),availability));
        tracker.failure(a);
        assertEquals(0,tracker.eligibleCount(List.of(0),availability));
        assertEquals(1,tracker.adaptiveSlots(64,64,List.of(0),availability),
                "One bounded rediscovery slot remains when no Peer currently qualifies");
        assertEquals(1,tracker.eligibleCount(List.of(1),availability));
    }

    @Test void newDiscoveredPeersEnterWithoutResettingKnownGoodScores() {
        var good=peer("8.8.8.8");
        var bad=peer("9.9.9.9");
        var newcomer=peer("1.1.1.1");
        var pool=new PeerHealthTracker(List.of(good,bad));
        pool.success(good,262144,100_000_000L);
        pool.failure(bad);
        pool.failure(bad);
        assertEquals(1,pool.mergeDiscovered(List.of(good,newcomer,newcomer),3));
        // bad is evicted (never succeeded, 2 consecutive failures), good stays
        assertEquals(2,pool.peerCount());
        assertEquals(List.of(good),pool.provenPeers());
        assertNotNull(pool.select(0,Set.of(good)));
        assertEquals(0,pool.mergeDiscovered(List.of(good,newcomer),3));
        var inherited=new PeerHealthTracker(pool.snapshotPeers(),pool);
        assertEquals(good,inherited.select(0,Set.of()));
    }

    @Test void fullPeerPoolOnlyEvictsUnprovenFailedIdleAddresses() {
        var good=peer("8.8.8.8");
        var bad=peer("9.9.9.9");
        var newcomer=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(good,bad));
        tracker.success(good);
        assertEquals(0,tracker.mergeDiscovered(List.of(newcomer),2));
        tracker.failure(bad);
        tracker.failure(bad);
        assertEquals(1,tracker.mergeDiscovered(List.of(newcomer),2));
        assertEquals(List.of(good,newcomer),tracker.snapshotPeers());
        assertEquals(2,tracker.peerCount());
    }

    @Test void verifiedThroughputFavorsUsefulPeerWithoutRemovingIsolation() {
        var fast=peer("8.8.8.8");
        var slow=peer("9.9.9.9");
        var tracker=new PeerHealthTracker(List.of(slow,fast));
        tracker.success(slow,64*1024,2_000_000_000L);
        tracker.success(fast,64*1024,100_000_000L);
        assertEquals(fast,tracker.select(0,Set.of()));
        var carried=new PeerHealthTracker(List.of(slow,fast),tracker);
        assertEquals(fast,carried.select(0,Set.of()));
    }

    @Test void fasterPeerBeatsStickySocketAndHighRttPeerWithoutHardBanningSlowSources() {
        var slow=peer("8.8.8.8");
        var fast=peer("9.9.9.9");
        var health=new PeerHealthTracker(List.of(slow,fast));
        health.success(fast,262144,100_000_000L);  // ~2560 KiB/s
        health.observeBlockRtt(fast,30_000_000L);
        health.success(slow,262144,4_000_000_000L); // ~64 KiB/s; sticky now
        health.success(slow,262144,4_000_000_000L);
        health.observeBlockRtt(slow,900_000_000L);
        assertEquals(fast,health.select(0,Set.of()),
                "Verified throughput and Block RTT outrank last-used socket affinity");
        var available=new PeerPieceAvailability(2,java.util.Map.of());
        assertEquals(fast,health.leaseBestWork(List.of(0,1),available).peer());
        health.release(fast);
        // The sole Peer known to have a rare Piece remains eligible even
        // after a long history of being slower than other Peers.
        var rare=new java.util.BitSet();rare.set(1);
        var common=new java.util.BitSet();common.set(0);
        var bits=new PeerPieceAvailability(2,java.util.Map.of(slow,rare,fast,common));
        assertEquals(slow,health.select(1,Set.of(),bits));
        var inherited=new PeerHealthTracker(List.of(slow,fast),health);
        assertEquals(fast,inherited.select(0,Set.of()),
                "Task-local throughput and RTT samples carry across rounds");
    }

    @Test void failuresCoolDownExponentiallyAndVerifiedSuccessResetsPenaltyWindow() {
        var now=new AtomicLong(1_000_000_000L);
        var a=peer("8.8.8.8");
        var b=peer("9.9.9.9");
        var health=new PeerHealthTracker(List.of(a,b),now::get);
        assertEquals(a,health.select(0,Set.of()));
        health.failure(a);
        assertEquals(b,health.select(0,Set.of()));
        assertNull(health.select(0,Set.of(b)),"Cooling Peer cannot be retried immediately");
        now.addAndGet(249_000_000L);
        assertNull(health.select(0,Set.of(b)));
        now.addAndGet(1_000_000L);
        assertEquals(a,health.select(0,Set.of(b)));
        health.failure(a);
        now.addAndGet(499_000_000L);
        assertNull(health.select(0,Set.of(b)),"Second consecutive failure uses longer cooldown");
        now.addAndGet(1_000_000L);
        assertEquals(a,health.select(0,Set.of(b)));
        health.success(a);
        assertEquals(a,health.select(0,Set.of(b)));
        assertNull(health.select(0,Set.of(a,b)));
    }

    @Test void mergeDiscoveredReplacesFailedIdlePeersEvenWhenPoolNotFull() {
        var good=peer("8.8.8.8");
        var failedA=peer("9.9.9.1");
        var failedB=peer("9.9.9.2");
        var newcomer=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(good,failedA,failedB));
        tracker.success(good);
        tracker.failure(failedA);
        tracker.failure(failedA);
        tracker.failure(failedB);
        tracker.failure(failedB);
        // Pool has 3 Peers, well below 256 limit, but failed idle Peers should be replaced
        assertEquals(1,tracker.mergeDiscovered(List.of(newcomer),256));
        assertEquals(3,tracker.peerCount());
        assertTrue(tracker.snapshotPeers().contains(newcomer));
        assertTrue(tracker.snapshotPeers().contains(good));
    }

    @Test void mergeDiscoveredNeverEvictsVerifiedPeers() {
        var verified=peer("8.8.8.8");
        var failed=peer("9.9.9.9");
        var newcomer=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(verified,failed));
        tracker.success(verified);
        tracker.failure(failed);
        tracker.failure(failed);
        // Even with many new Peers, verified one must stay
        var result=tracker.mergeDiscovered(List.of(newcomer),2);
        assertTrue(tracker.snapshotPeers().contains(verified));
    }

    @Test void mergeDiscoveredNeverEvictsActiveLeasePeers() {
        var active=peer("8.8.8.8");
        var failed=peer("9.9.9.9");
        var newcomer=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(active,failed));
        tracker.lease(0,Set.of(),null);
        tracker.failure(failed);
        tracker.failure(failed);
        // Active lease Peer must not be evicted even if it has failures
        tracker.failure(active);
        tracker.failure(active);
        var result=tracker.mergeDiscovered(List.of(newcomer),2);
        assertTrue(tracker.snapshotPeers().contains(active));
    }

    @Test void swarmAmnestyLiftsCooldownAfterAWholeRoundWithoutProgress() {
        var now=new AtomicLong(1_000_000_000L);
        var a=peer("8.8.8.8");
        var b=peer("9.9.9.9");
        var health=new PeerHealthTracker(List.of(a,b),now::get);
        health.failure(a);
        health.failure(a);
        health.failure(b);
        health.failure(b);
        assertEquals(2,health.degradedCount());
        assertNull(health.select(0,Set.of()),
                "Both Peers are cooling immediately after consecutive failures");
        health.grantSwarmAmnesty();
        assertEquals(2,health.degradedCount(),
                "Amnesty lifts cooldowns but keeps failure counters so dead Peers stay evictable");
        assertEquals(a,health.select(0,Set.of()),
                "A lifted cooldown must make the Peer leaseable without waiting");
    }

    @Test void mergeDiscoveredPrefersReplacingMostFailedIdlePeers() {
        var failedA=peer("9.9.9.1");
        var failedB=peer("9.9.9.2");
        var newcomer=peer("1.1.1.1");
        var tracker=new PeerHealthTracker(List.of(failedA,failedB));
        tracker.failure(failedA);
        tracker.failure(failedA);
        tracker.failure(failedA);
        tracker.failure(failedB);
        tracker.failure(failedB);
        assertEquals(1,tracker.mergeDiscovered(List.of(newcomer),2));
        // The Peer with more failures (failedA with 3) should be replaced first
        assertTrue(tracker.snapshotPeers().contains(newcomer));
        assertFalse(tracker.snapshotPeers().contains(failedA));
        assertTrue(tracker.snapshotPeers().contains(failedB));
    }
}
