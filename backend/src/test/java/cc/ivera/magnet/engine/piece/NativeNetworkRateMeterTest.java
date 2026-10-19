package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeNetworkRateMeterTest {
    @Test void frequentUiPollsDoNotFabricateInstantaneousZeroes() {
        var meter=new NativeNetworkRateMeter();
        long start=1_000_000_000L;
        assertEquals(0,meter.sample(0,start));
        assertTrue(meter.sample(16_384,start+100_000_000L)>0);
        double first=meter.sample(65_536,start+600_000_000L);
        assertTrue(first>0);
        for(int n=1;n<=4;n++)
            assertTrue(meter.sample(65_536,start+600_000_000L+n*100_000_000L)>0,
                "Polling with no bytes at 100ms must retain recent throughput");
        assertTrue(meter.sample(80_000,start+1_300_000_000L)>0);
        assertEquals(0,meter.sample(80_000,start+7_000_000_000L));
        assertTrue(meter.sample(150_000,start+7_700_000_000L)>0,
                "Meter must recover when Peer traffic resumes");
    }
    @Test void rejectsCounterRegressionsAndNegativeInputs() {
        var m=new NativeNetworkRateMeter();
        assertThrows(IllegalArgumentException.class,()->m.sample(-1,1));
        assertThrows(IllegalArgumentException.class,()->m.sample(2,-1));
        m.sample(100,1_000_000_000L);
        assertEquals(0,m.sample(90,1_600_000_000L));
        assertEquals(0,m.sample(100,1_700_000_000L));
    }
}
