package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeWorkerJobManagerTest {
    @Test
    void socketTimeoutSkipsSameRoundRetryButOrdinaryIoRetries() {
        assertFalse(NativeWorkerJobManager.shouldRetryContinuousFailure(
                new SocketTimeoutException("peer stalled")));
        assertTrue(NativeWorkerJobManager.shouldRetryContinuousFailure(
                new IOException("connection reset")));
    }

    @Test
    void continuousBackoffUsesRetryAndStallWindowsWithinBudget() {
        assertEquals(0,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                0,0,1_000_000_000L));
        assertEquals(200,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                1,0,1_000_000_000L));
        assertEquals(400,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                2,0,1_000_000_000L));
        assertEquals(400,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                0,1,1_000_000_000L));
        assertEquals(800,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                0,2,1_000_000_000L));
        assertEquals(123,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                1,2,123_500_000L));
        assertEquals(0,NativeWorkerJobManager.boundedContinuousBackoffMillis(
                1,1,0));
        assertEquals(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE),
                NativeWorkerJobManager.boundedContinuousBackoffMillis(
                        Integer.MAX_VALUE,Integer.MAX_VALUE,Long.MAX_VALUE));
    }

    @Test
    void socketTimeoutAdvancesToNextContinuousRound() {
        long owner=7;
        UUID task=UUID.randomUUID();
        long revision=1;
        var ready=new NativeTaskLifecycleService.TaskState(
                task,"READY",revision,0,1,0);
        var completed=new NativeTaskLifecycleService.TaskState(
                task,"COMPLETED",revision,1,1,0);
        var state=new AtomicReference<>(ready);
        var invocations=new AtomicInteger();
        var taskReads=new AtomicInteger();
        NativeWorkerJobManager.TaskAccess tasks=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long ignored,UUID id) {
                taskReads.incrementAndGet();
                return state.get();
            }
            @Override public NativeTaskLifecycleService.TaskState pause(
                    long ignored,UUID id,long ignoredRevision) {
                var paused=new NativeTaskLifecycleService.TaskState(
                        task,"PAUSED",revision,0,1,0);
                state.set(paused);
                return paused;
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(
                    long ignored,UUID id,long ignoredRevision) {
                state.set(ready);
                return ready;
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(
                    long ignored,UUID id,long ignoredRevision) {
                return state.get();
            }
        };
        var flags=new cc.ivera.magnet.engine.config.NativeTorrentProperties();
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        flags.setContinuousWorkerEnabled(true);
        flags.setContinuousMaxPieces(4);
        flags.setContinuousMaxRounds(2);
        flags.setContinuousMaxSeconds(10);
        flags.setContinuousStalledRounds(2);
        NativeWorkerJobManager.RoundInvoker invoker=(ignoredOwner,ignoredTask,
                ignoredRevision,ignoredPieces)->{
            int call=invocations.incrementAndGet();
            if(call==1)throw new SocketTimeoutException("peer stalled");
            if(taskReads.get()<4)throw new IOException("same-round retry");
            state.set(completed);
            return new NativeTaskWorkerRoundService.RoundReport(1,true,null,completed);
        };
        var manager=new NativeWorkerJobManager(invoker,tasks,flags,1,1,1);
        try {
            UUID job=manager.submitContinuous(owner,task,revision,1,2,10);
            var snapshot=manager.await(owner,job,Duration.ofSeconds(5));
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,snapshot.state());
            assertEquals(2,invocations.get());
        }finally {
            manager.close();
        }
    }
}
