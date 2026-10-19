package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PeerLatencyWindowTest {
    @Test void p95TracksRecentTailAndForgetsStaleSlowBlocks() {
        var meter=new PeerLatencyWindow();
        assertEquals(0,meter.p95Nanos());
        for(int i=0;i<31;i++)meter.add(TimeUnit.MILLISECONDS.toNanos(35));
        meter.add(TimeUnit.MILLISECONDS.toNanos(900));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(35),meter.p95Nanos());
        meter.add(TimeUnit.MILLISECONDS.toNanos(1200));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(900),meter.p95Nanos());
        for(int i=0;i<32;i++)meter.add(TimeUnit.MILLISECONDS.toNanos(25));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25),meter.p95Nanos());
        assertEquals(32,meter.count());
        meter.add(0);
        assertEquals(32,meter.count());
    }
}
