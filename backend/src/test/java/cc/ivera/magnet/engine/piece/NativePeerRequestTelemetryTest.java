package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePeerRequestTelemetryTest {
    @Test void samplesRealInFlightOccupancyAndRttPercentilesWithoutLeakingActors() throws Exception {
        var stats=new NativePeerRequestTelemetry();
        var actor=Thread.currentThread();
        stats.observe(actor,16,32,16,false,0);
        assertEquals(50,stats.snapshot().occupancyPercent());
        assertEquals(16,stats.snapshot().sentBlocks());
        assertEquals(16,stats.snapshot().outstandingBlocks());
        stats.observe(actor,15,32,0,true,TimeUnit.MILLISECONDS.toNanos(18));
        stats.observe(actor,14,32,0,true,TimeUnit.MILLISECONDS.toNanos(48));
        stats.observe(actor,13,32,0,true,TimeUnit.MILLISECONDS.toNanos(500));
        assertEquals(3,stats.snapshot().ackedBlocks());
        assertEquals(64,stats.snapshot().rttP50Millis());
        assertEquals(512,stats.snapshot().rttP95Millis());
        stats.leave(actor);
        assertEquals(0,stats.snapshot().outstandingBlocks());
        assertEquals(0,stats.snapshot().capacityBlocks());
        assertEquals(3,stats.snapshot().ackedBlocks());
    }

    @Test void actorQueuesRemainIndependentDuringParallelRequests() throws Exception {
        var stats=new NativePeerRequestTelemetry();
        var barrier=new CountDownLatch(2);
        var released=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var p1=executor.submit(()->{
                stats.observe(Thread.currentThread(),8,16,8,false,0);
                barrier.countDown();released.await();
                stats.leave(Thread.currentThread());
                return null;
            });
            var p2=executor.submit(()->{
                stats.observe(Thread.currentThread(),12,16,12,false,0);
                barrier.countDown();released.await();
                stats.leave(Thread.currentThread());
                return null;
            });
            assertTrue(barrier.await(2,TimeUnit.SECONDS));
            assertEquals(20,stats.snapshot().outstandingBlocks());
            assertEquals(32,stats.snapshot().capacityBlocks());
            assertEquals(62,stats.snapshot().occupancyPercent());
            released.countDown();
            p1.get(2,TimeUnit.SECONDS);
            p2.get(2,TimeUnit.SECONDS);
            assertEquals(0,stats.snapshot().outstandingBlocks());
        }finally {released.countDown();executor.shutdownNow();}
    }
}
