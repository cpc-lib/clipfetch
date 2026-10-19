package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeerBlockPipelineControllerTest {
    @Test void maintainsWarmWindowAndRefillsBeforeCurrentPieceFinishes() {
        var pipeline=new PeerBlockPipelineController();
        assertEquals(16,pipeline.target());
        assertEquals(16,pipeline.nextBatch(0));
        assertEquals(4,pipeline.nextBatch(12));
        long now=1_000_000_000L;
        for(int i=0;i<16;i++) {
            now+=5_000_000L;
            pipeline.received(16384,10_000_000L,now);
        }
        assertEquals(16,pipeline.acknowledged());
        assertTrue(pipeline.target()>=16);
        assertTrue(pipeline.target()<=64);
        assertTrue(pipeline.nextBatch(4)>0);
        assertEquals(0,pipeline.nextBatch(pipeline.target()));
    }

    @Test void highRttSlowPeerUsesBoundedWindowWithoutStarvingCompletely() {
        var slow=new PeerBlockPipelineController();
        long now=1_000_000_000L;
        for(int i=0;i<20;i++) {
            now+=300_000_000L;
            slow.received(16384,300_000_000L,now);
        }
        assertEquals(8,slow.target());
        assertEquals(8,slow.nextBatch(0));
        assertEquals(0,slow.nextBatch(8));
        assertThrows(IllegalArgumentException.class,()->slow.nextBatch(65));
        assertThrows(IllegalArgumentException.class,()->slow.received(0,0,1));
    }
}
