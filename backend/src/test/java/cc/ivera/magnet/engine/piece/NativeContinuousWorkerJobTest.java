package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativeContinuousWorkerJobTest {
    private static final long OWNER=58L;
    private final UUID task=UUID.randomUUID();

    private static NativeTorrentProperties flags() {
        var flags=new NativeTorrentProperties();
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        flags.setContinuousWorkerEnabled(true);
        return flags;
    }

    private static final class FakeTasks implements NativeWorkerJobManager.TaskAccess {
        final Map<UUID,NativeTaskLifecycleService.TaskState> db=new HashMap<>();
        final long owner;
        FakeTasks(long owner,UUID id,long total) {
            this.owner=owner;
            db.put(id,new NativeTaskLifecycleService.TaskState(id,"READY",1,0,total,0));
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState get(long user,UUID id) {
            if(user!=owner || !db.containsKey(id))throw new NoSuchElementException();
            return db.get(id);
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState prepare(long user,UUID id,long rev) {
            var before=get(user,id);
            if(!"PAUSED".equals(before.state()) || before.revision()!=rev)
                throw new IllegalStateException("Invalid prepare revision");
            var after=new NativeTaskLifecycleService.TaskState(id,"READY",rev+1,
                    before.verifiedBytes(),before.selectedBytes(),before.wireBytes());
            db.put(id,after);
            return after;
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState pause(long user,UUID id,long rev) {
            var before=get(user,id);
            if(!("READY".equals(before.state()) || "RUNNING".equals(before.state()))
                    || before.revision()!=rev)
                throw new IllegalStateException("Invalid pause revision");
            var after=new NativeTaskLifecycleService.TaskState(id,"PAUSED",rev+1,
                    before.verifiedBytes(),before.selectedBytes(),before.wireBytes());
            db.put(id,after);
            return after;
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState fenceOrphan(
                long user,UUID id,long revision) {
            throw new UnsupportedOperationException();
        }
        synchronized NativeTaskWorkerRoundService.RoundReport round(long user,UUID id,long rev,
                                                                     int amount) {
            var before=get(user,id);
            if(!"READY".equals(before.state()) || before.revision()!=rev)
                throw new IllegalStateException("Stale Worker");
            long newBytes=Math.min(before.selectedBytes(),before.verifiedBytes()+amount);
            var after=new NativeTaskLifecycleService.TaskState(id,
                    newBytes==before.selectedBytes()?"COMPLETED":"PAUSED",
                    rev+2,newBytes,before.selectedBytes(),
                    before.wireBytes()+newBytes-before.verifiedBytes());
            db.put(id,after);
            return new NativeTaskWorkerRoundService.RoundReport(2,true,
                    new PieceDownloadCoordinator.RoundResult(1,1,1,List.of(),
                            newBytes,before.selectedBytes(),
                            100.0*newBytes/before.selectedBytes()),after);
        }
    }

    @Test void repeatsRoundsViaCasUntilCompletedAndReportsRealVerifiedSpeed() throws Exception {
        var data=new FakeTasks(OWNER,task,12);
        AtomicInteger attempts=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            attempts.incrementAndGet();
            return data.round(owner,id,revision,4);
        },data,flags(),1,2,1)) {
            UUID job=manager.submitContinuous(OWNER,task,1,2,8,30);
            var completed=manager.await(OWNER,job,Duration.ofSeconds(2));
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,completed.state());
            assertEquals(3,attempts.get());
            assertEquals("COMPLETED",data.get(OWNER,task).state());
            assertEquals(12,data.get(OWNER,task).verifiedBytes());
            var metrics=manager.metrics(OWNER,job);
            assertEquals(3,metrics.rounds());
            assertEquals(3,metrics.committedPieces());
            assertEquals(12,metrics.verifiedBytes());
            assertEquals(12,metrics.selectedBytes());
            assertEquals(12,metrics.wireBytes());
            assertTrue(metrics.averageBytesPerSecond()>=0);
            assertTrue(metrics.recentBytesPerSecond()>=0);
            assertThrows(NoSuchElementException.class,()->manager.metrics(OWNER+1,job));
            assertThrows(IllegalStateException.class,
                    ()->manager.submitContinuous(OWNER,task,1,2,8,30));
        }
    }

    @Test void zeroProgressStopsAfterThreeRoundsAndPausesReadyTask() throws Exception {
        var data=new FakeTasks(OWNER,task,100);
        AtomicInteger rounds=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,version,pieces)->{
            rounds.incrementAndGet();
            var current=data.get(owner,id);
            return new NativeTaskWorkerRoundService.RoundReport(0,false,null,current);
        },data,flags(),1,2,1)) {
            var id=manager.submitContinuous(OWNER,task,1,4,64,30);
            var outcome=manager.await(OWNER,id,Duration.ofSeconds(4));
            assertEquals(NativeWorkerJobManager.State.PARTIAL,outcome.state());
            assertEquals(3,rounds.get());
            assertEquals(3,manager.metrics(OWNER,id).rounds());
            assertEquals("PAUSED",data.get(OWNER,task).state());
            assertEquals(0,manager.metrics(OWNER,id).verifiedBytes());
            assertTrue(outcome.error().contains("No verified"));
        }
    }

    @Test void cancelWhileFirstRoundIsStillRunningPreventsAnySecondRound() throws Exception {
        var data=new FakeTasks(OWNER,task,20);
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var finished=new CountDownLatch(1);
        AtomicInteger rounds=new AtomicInteger();
        var error=new AtomicReference<Throwable>();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            rounds.incrementAndGet();
            started.countDown();
            while(release.getCount()>0) {
                try {release.await(30,TimeUnit.MILLISECONDS);}
                catch(InterruptedException ignored) {}
            }
            finished.countDown();
            try {return data.round(owner,id,revision,4);}
            catch(Throwable e){error.set(e);throw e;}
        },data,flags(),1,2,1)) {
            UUID job=manager.submitContinuous(OWNER,task,1,4,10,20);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            assertEquals(NativeWorkerJobManager.State.CANCELLED,manager.cancel(OWNER,job).state());
            assertEquals("PAUSED",data.get(OWNER,task).state());
            release.countDown();
            assertTrue(finished.await(1,TimeUnit.SECONDS));
            assertEquals(1,rounds.get());
            assertEquals(NativeWorkerJobManager.State.CANCELLED,
                    manager.status(OWNER,job).state());
            assertEquals(0,manager.metrics(OWNER,job).committedPieces());
        }finally{release.countDown();}
    }

    @Test void roundBudgetExhaustionKeepsCommittedPiecesAndContinuesSession() throws Exception {
        var data=new FakeTasks(OWNER,task,8);
        AtomicInteger attempts=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            int call=attempts.incrementAndGet();
            if(call==1) {
                // A fenced round commits Pieces, the runner pauses the task, then the
                // round network deadline throws. The session must keep the progress
                // as a partial round instead of failing the whole bounded job.
                data.round(owner,id,revision,4);
                throw new SocketTimeoutException(NativeNetworkBudget.DEADLINE_EXCEEDED);
            }
            return data.round(owner,id,revision,4);
        },data,flags(),1,2,1)) {
            UUID job=manager.submitContinuous(OWNER,task,1,2,8,30);
            var completed=manager.await(OWNER,job,Duration.ofSeconds(4));
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,completed.state());
            assertEquals(2,attempts.get());
            assertEquals("COMPLETED",data.get(OWNER,task).state());
            assertEquals(8,data.get(OWNER,task).verifiedBytes());
            assertEquals(8,manager.metrics(OWNER,job).verifiedBytes());
        }
    }

    @Test void refusesInvalidBudgetsOrDisabledContinuousMode() {
        var data=new FakeTasks(OWNER,task,20);
        var flags=flags();
        flags.setContinuousWorkerEnabled(false);
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->
                data.round(owner,id,rev,4),data,flags,1,1,1)) {
            assertThrows(IllegalStateException.class,
                    ()->manager.submitContinuous(OWNER,task,1,1,3,20));
            flags.setContinuousWorkerEnabled(true);
            assertThrows(IllegalArgumentException.class,
                    ()->manager.submitContinuous(OWNER,task,1,4,65,20));
            assertThrows(IllegalArgumentException.class,
                    ()->manager.submitContinuous(OWNER,task,1,4,2,9));
            assertThrows(IllegalArgumentException.class,
                    ()->manager.submitContinuous(OWNER,task,1,5,2,20));
            assertThrows(NoSuchElementException.class,
                    ()->manager.submitContinuous(OWNER+1,task,1,2,2,20));
        }
    }
}
