package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerCentricPieceQueueTest {
    private static InetSocketAddress peer(int index) {
        return InetSocketAddress.createUnresolved("8.8.8."+index,6881);
    }

    @Test void bitfieldAwarePeerActorsReserveEachPieceOnlyOnce() throws Exception {
        var left=peer(1);var right=peer(2);
        var a=new BitSet();a.set(0);a.set(2);
        var b=new BitSet();b.set(1);b.set(3);
        var availability=new PeerPieceAvailability(4,Map.of(left,a,right,b));
        var health=new PeerHealthTracker(List.of(left,right));
        var queue=new PeerCentricPieceQueue(List.of(0,1,2,3));
        ExecutorService threads=Executors.newFixedThreadPool(4);
        try {
            var jobs=new ArrayList<Future<PeerCentricPieceQueue.Assignment>>();
            for(int i=0;i<4;i++)jobs.add(threads.submit(()->queue.claim(health,availability)));
            var pieces=new HashSet<Integer>();
            for(var job:jobs) {
                var work=job.get(2,TimeUnit.SECONDS);
                assertNotNull(work);
                assertTrue(availability.confirmed(work.peer(),work.piece()));
                assertTrue(pieces.add(work.piece()),"No duplicate primary Piece leases");
                health.release(work.peer());
            }
            assertEquals(Set.of(0,1,2,3),pieces);
            assertEquals(0,queue.remaining());
            assertEquals(4,queue.assigned());
            assertNull(queue.claim(health,availability));
        }finally {threads.shutdownNow();}
    }

    @Test void stableActorPrefersItsVerifiedConnectionForNextPiece() {
        var preferred=peer(1);
        var stranger=peer(2);
        var health=new PeerHealthTracker(List.of(preferred,stranger));
        health.success(preferred,262144,10_000_000);
        var availability=new PeerPieceAvailability(8,Map.of());
        var queue=new PeerCentricPieceQueue(List.of(3,4,5));
        var work=queue.claim(health,availability);
        assertEquals(preferred,work.peer());
        assertEquals(3,work.piece());
        health.release(work.peer());
        var next=queue.claim(health,availability);
        assertEquals(preferred,next.peer());
        assertEquals(4,next.piece());
        health.release(next.peer());
    }

    @Test void unavailablePeerNeverGetsNegativeBitfieldLease() {
        var left=peer(1);
        var present=new BitSet();present.set(1);
        var health=new PeerHealthTracker(List.of(left));
        var availability=new PeerPieceAvailability(2,Map.of(left,present));
        var queue=new PeerCentricPieceQueue(List.of(0,1));
        assertEquals(1,queue.claim(health,availability).piece());
        health.release(left);
        var fallback=queue.claim(health,availability);
        assertEquals(0,fallback.piece());
        assertNull(fallback.peer(),"Known-negative Peers cannot claim unavailable Pieces");
        assertEquals(2,queue.assigned());
    }

    @Test void invalidAndDuplicateAssignmentsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new PeerCentricPieceQueue(List.of(1,1)));
        assertThrows(IllegalArgumentException.class,()->new PeerCentricPieceQueue(List.of()));
    }
}
