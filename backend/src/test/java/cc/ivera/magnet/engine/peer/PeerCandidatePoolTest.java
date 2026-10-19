package cc.ivera.magnet.engine.peer;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class PeerCandidatePoolTest {
    @Test void deduplicatesAcrossSourcesAndEnforcesLimit() {
        var pool = new PeerCandidatePool(1, new TrackerAddressPolicy(true));
        var first = InetSocketAddress.createUnresolved("127.0.0.1", 6881);
        assertTrue(pool.offer(first, PeerCandidatePool.Source.DHT));
        assertTrue(pool.offer(first, PeerCandidatePool.Source.HTTP_TRACKER));
        assertFalse(pool.offer(InetSocketAddress.createUnresolved("127.0.0.1", 6882),
                PeerCandidatePool.Source.UDP_TRACKER));
        assertEquals(1, pool.size());
        assertEquals(2, pool.snapshot().getFirst().sources().size());
    }
    @Test void blocksLoopbackByDefault() {
        var pool = new PeerCandidatePool(10, new TrackerAddressPolicy(false));
        assertFalse(pool.offer(InetSocketAddress.createUnresolved("127.0.0.1", 6881),
                PeerCandidatePool.Source.DHT));
    }
}
