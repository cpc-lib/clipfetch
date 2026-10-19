package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeerBlockRequestWindowTest {
    private static List<PieceBlockPlanner.Block> blocks(int pieces) {
        List<PieceBlockPlanner.Block> data=new ArrayList<>();
        for(int i=0;i<pieces;i++)
            data.add(new PieceBlockPlanner.Block(0,i*PieceBlockPlanner.BLOCK_BYTES,
                    PieceBlockPlanner.BLOCK_BYTES));
        return data;
    }

    @Test void keepsOutstandingBoundedDespiteLongPiecesAndOutOfOrderResponses() throws Exception {
        var pipeline=new PeerBlockRequestWindow(blocks(96));
        assertEquals(16,pipeline.fill().size());
        for(int i=15;i>=0;i--) {
            pipeline.acknowledge(0,0,i*PieceBlockPlanner.BLOCK_BYTES,
                    PieceBlockPlanner.BLOCK_BYTES);
        }
        assertEquals(32,pipeline.window());
        int seen=16;
        while(!pipeline.complete()) {
            var reserved=pipeline.fill();
            assertFalse(reserved.isEmpty());
            assertTrue(pipeline.inFlight()<=PeerBlockRequestWindow.MAX);
            for(var block:reserved) {
                pipeline.acknowledge(0,0,block.begin(),block.length());
                seen++;
            }
        }
        assertEquals(96,seen);
        assertEquals(0,pipeline.inFlight());
        assertTrue(pipeline.fill().isEmpty());
        assertEquals(64,pipeline.window());
    }

    @Test void resumingSparseBlocksNeverRequestsAlreadyReceivedOffsets() throws Exception {
        var sparse=List.of(blocks(16).get(0),blocks(16).get(5),blocks(16).get(15));
        var queue=new PeerBlockRequestWindow(sparse);
        assertEquals(sparse,queue.fill());
        assertThrows(IOException.class,()->queue.acknowledge(0,0,16384,16384));
        queue.acknowledge(0,0,15*16384,16384);
        queue.acknowledge(0,0,0,16384);
        queue.acknowledge(0,0,5*16384,16384);
        assertTrue(queue.complete());
        assertEquals(0,queue.inFlight());
    }

    @Test void blockDeadlineReclaimsStalledRequestsEvenWhenPeerSendsOtherMessages()
            throws Exception {
        var nanos=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var queue=new PeerBlockRequestWindow(blocks(24),nanos::get);
        assertEquals(16,queue.fill().size());
        assertEquals(5_000_000_000L,queue.nextDeadlineNanos());
        nanos.addAndGet(100_000_000L);
        queue.acknowledge(0,0,0,16384);
        assertTrue(queue.estimatedRttNanos()>0);
        assertEquals(16,queue.window());
        assertTrue(queue.nextDeadlineNanos()<=5_000_000_000L);
        nanos.set(6_000_000_000L);
        assertTrue(nanos.get()>=queue.nextDeadlineNanos(),
                "Unanswered block stays expired despite an earlier ACK");
        assertTrue(queue.targetOutstanding()<=PeerBlockRequestWindow.MAX);
    }

    @Test void firstRepliesNeverCollapseWarmPipelineFromSixteenToFour()
            throws Exception {
        var nanos=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var queue=new PeerBlockRequestWindow(blocks(96),nanos::get);
        assertEquals(16,queue.fill().size());
        for(int i=0;i<4;i++) {
            nanos.addAndGet(20_000_000L);
            queue.acknowledge(0,0,i*16384,16384);
        }
        assertEquals(16,queue.targetOutstanding(),
                "The first ACKs must not starve the request queue");
        assertEquals(4,queue.fill().size(),
                "Refill after ACKs instead of waiting for the first whole Piece");
        assertEquals(16,queue.inFlight());
        assertEquals(80_000_000L,queue.lastObservedRttNanos(),
                "Fourth queued Block was sent at start, so its RTT is 80ms");
    }

    @Test void throughputBasedWindowRemainsBoundedOnVeryFastPeer() throws Exception {
        var nanos=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var queue=new PeerBlockRequestWindow(blocks(128),nanos::get);
        queue.fill();
        for(int i=0;i<16;i++){
            nanos.addAndGet(1_000_000L);
            queue.acknowledge(0,0,i*16384,16384);
        }
        assertTrue(queue.targetOutstanding()>=4);
        assertTrue(queue.targetOutstanding()<=PeerBlockRequestWindow.MAX);
    }

    @Test void refusesUnrequestedDuplicateWrongLengthAndWrongPiece() throws Exception {
        var pipeline=new PeerBlockRequestWindow(blocks(40));
        pipeline.fill();
        assertThrows(IOException.class,()->pipeline.acknowledge(0,1,0,16384));
        assertThrows(IOException.class,()->pipeline.acknowledge(0,0,16*16384,16384));
        assertThrows(IOException.class,()->pipeline.acknowledge(0,0,0,1));
        pipeline.acknowledge(0,0,0,16384);
        assertThrows(IOException.class,()->pipeline.acknowledge(0,0,0,16384));
        assertEquals(15,pipeline.inFlight());
    }
}
