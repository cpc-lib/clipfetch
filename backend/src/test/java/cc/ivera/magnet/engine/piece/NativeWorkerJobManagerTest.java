package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NativeWorkerJobManagerTest {
    private static final long USER=31;
    private final UUID task=UUID.randomUUID();

    private static NativeTorrentProperties flags() {
        var p=new NativeTorrentProperties();
        p.setEnabled(true);
        p.setWorkerRoundEnabled(true);
        p.setAsyncWorkerEnabled(true);
        return p;
    }
    private static NativeTaskLifecycleService.TaskState state(UUID id,String name,long rev) {
        return new NativeTaskLifecycleService.TaskState(id,name,rev,0,8,0);
    }
    private static final class InMemoryTasks implements NativeWorkerJobManager.TaskAccess {
        private final Map<UUID,NativeTaskLifecycleService.TaskState> records=new HashMap<>();
        private final Map<UUID,Long> owners=new HashMap<>();
        synchronized void put(long owner,UUID id,String name,long revision) {
            owners.put(id,owner);
            records.put(id,state(id,name,revision));
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState get(long owner,UUID id) {
            if(owner<=0 || !Objects.equals(owners.get(id),owner))
                throw new NoSuchElementException("Wrong owner");
            return records.get(id);
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState pause(
                long owner,UUID id,long revision) {
            var before=get(owner,id);
            if(before.revision()!=revision ||
                    !("RUNNING".equals(before.state()) || "READY".equals(before.state())))
                throw new IllegalStateException("Stale pause");
            var after=state(id,"PAUSED",revision+1);
            records.put(id,after);
            return after;
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState prepare(
                long owner,UUID id,long revision) {
            var before=get(owner,id);
            if(before.revision()!=revision || !"PAUSED".equals(before.state()))
                throw new IllegalStateException("Stale preparation");
            var after=state(id,"READY",revision+1);
            records.put(id,after);
            return after;
        }
        @Override public synchronized NativeTaskLifecycleService.TaskState fenceOrphan(
                long owner,UUID id,long revision) {
            var before=get(owner,id);
            if(before.revision()!=revision || !"RUNNING".equals(before.state()))
                throw new IllegalStateException("Not an orphan");
            var after=state(id,"PAUSED",revision+1);
            records.put(id,after);
            return after;
        }
    }
    private static NativeTaskWorkerRoundService.RoundReport completed(UUID task) {
        return new NativeTaskWorkerRoundService.RoundReport(1,true,
                new PieceDownloadCoordinator.RoundResult(1,1,1,List.of(),8,8,100),
                new NativeTaskLifecycleService.TaskState(task,"COMPLETED",2,8,8,8));
    }

    @Test void networkSpeedUpdatesBeforeCheckpointOrDbProgressIsCommitted() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var settings=flags();
        settings.setContinuousWorkerEnabled(true);
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var received=new java.util.concurrent.atomic.AtomicLong();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            started.countDown();
            try {release.await(3,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Cancelled test transfer",interrupted);
            }
            return completed(id);
        },memory,settings,1,2,1)) {
            manager.setNetworkTelemetry((owner,id)->{
                assertEquals(USER,owner);
                assertEquals(task,id);
                return received.get();
            });
            manager.setPeerTelemetry((owner,id)->{
                assertEquals(USER,owner);
                assertEquals(task,id);
                return new NativeTaskWorkerRoundService.PeerStatus(24,9,5,2);
            });
            manager.setStagedTelemetry((owner,id)->
                    new NativeTaskWorkerRoundService.StagedProgress(7,7L*256*1024));
            manager.setConnectionTelemetry((owner,id)->
                    new PeerPieceDownloadSession.TaskConnectionStats(3,18,5,2,0,0,0,0));
            var transport=new NativePeerRequestTelemetry();
            transport.observe(Thread.currentThread(),8,16,8,true,
                    TimeUnit.MILLISECONDS.toNanos(35));
            manager.setRequestQueueTelemetry((owner,id)->transport.snapshot());
            var job=manager.submitContinuous(USER,task,1,1,2,10);
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var first=manager.metrics(USER,job);
            assertEquals(0,first.networkBytesPerSecond());
            assertEquals(0,first.verifiedBytes());
            received.addAndGet(16*1024);
            Thread.sleep(15);
            var live=manager.metrics(USER,job);
            assertEquals(16*1024,live.receivedPayloadBytes());
            assertEquals(7,live.stagedPieces());
            assertEquals(7L*256*1024,live.stagedVerifiedBytes());
            assertEquals(3,live.openedPeerSockets());
            assertEquals(18,live.reusedPeerSockets());
            assertEquals(5,live.crossRoundPeerReuses());
            assertEquals(2,live.idlePeerSockets());
            assertEquals(8,live.peerRequestQueue().outstandingBlocks());
            assertEquals(50,live.peerRequestQueue().occupancyPercent());
            assertEquals(64,live.peerRequestQueue().rttP95Millis());
            assertEquals(8,live.peerRequestQueue().sentBlocks());
            assertEquals(24,live.peerPoolCount());
            assertEquals(9,live.verifiedPeerCount());
            assertEquals(5,live.degradedPeerCount());
            assertEquals(2,live.peerRefreshes());
            assertTrue(live.networkBytesPerSecond()>0,
                    "Live network speed must not wait for 64 Piece checkpoint batching");
            assertEquals(0,live.verifiedBytes());
            assertEquals(0,live.recentBytesPerSecond());
            assertThrows(NoSuchElementException.class,()->manager.metrics(USER+1,job));
            manager.cancel(USER,job);
            assertEquals(0,manager.metrics(USER,job).networkBytesPerSecond());
            assertEquals(0,manager.metrics(USER,job).stagedVerifiedBytes());
            assertEquals(0,manager.metrics(USER,job).openedPeerSockets());
        }finally {release.countDown();}
    }

    @Test void metricsReflectCommittedBytesWhileRoundIsStillRunning() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var committed=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            synchronized(memory) {
                memory.records.put(id,new NativeTaskLifecycleService.TaskState(
                        id,"RUNNING",revision+1,4,8,4));
            }
            committed.countDown();
            try {release.await();}
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted test worker",e);
            }
            return completed(id);
        },memory,flags(),1,2,1)) {
            var job=manager.submit(USER,task,1,1);
            assertTrue(committed.await(2,TimeUnit.SECONDS));
            var progress=manager.metrics(USER,job);
            assertEquals(NativeWorkerJobManager.State.RUNNING,progress.state());
            assertEquals(0,progress.rounds());
            assertEquals(4,progress.verifiedBytes());
            assertEquals(4,progress.wireBytes());
            assertTrue(progress.recentBytesPerSecond()>0);
            assertTrue(progress.averageBytesPerSecond()>0);
            assertEquals(4,manager.status(USER,job).verifiedBytes());
            assertThrows(NoSuchElementException.class,()->manager.metrics(USER+1,job));
            manager.cancel(USER,job);
            synchronized(memory) {
                memory.records.put(task,new NativeTaskLifecycleService.TaskState(
                        task,"PAUSED",4,8,8,8));
            }
            assertEquals(4,manager.metrics(USER,job).verifiedBytes(),
                    "Cancelled jobs must not absorb late worker progress");
        }finally {release.countDown();}
    }

    @Test void lostLeaseBeforeNextFencedRoundStopsSafelyWithoutPeerFailure() {
        var config=flags();
        config.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,rev,max)->{
                    calls.incrementAndGet();
                    throw new NativeInstanceLeaseUnavailableException(
                            "Native Worker instance heartbeat/lease is unhealthy");
                },memory,config,1,2,1)) {
            var job=manager.submitContinuous(USER,task,1,1,3,10);
            var finalState=manager.await(USER,job,Duration.ofSeconds(3));
            assertEquals(NativeWorkerJobManager.State.CANCELLED,finalState.state());
            assertEquals("PAUSED",memory.get(USER,task).state());
            assertTrue(finalState.error().contains("lease unavailable"));
            assertEquals(1,calls.get(),"Never retry after the worker loses its lease");
        }
    }

    @Test void heartbeatAdmissionFailsClosedBeforeTaskReadOrDispatch() {
        var properties=flags();
        properties.setInstanceHeartbeatEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((u,t,rev,max)->{
                    calls.incrementAndGet();return completed(t);
                },memory,properties,1,2,2)) {
            assertThrows(IllegalStateException.class,()->manager.submit(USER,task,1,1));
            assertEquals(0,manager.queueHealth().trackedJobs());
            manager.setInstanceAdmissionHealthy(()->true);
            var job=manager.submit(USER,task,1,1);
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,job,Duration.ofSeconds(2)).state());
            assertEquals(1,calls.get());
            manager.setInstanceAdmissionHealthy(()->false);
            assertThrows(IllegalStateException.class,()->manager.submit(USER,UUID.randomUUID(),1,1));
            assertEquals(1,calls.get());
        }
    }

    @Test void continuousSessionStartsOnceAndAlwaysClosesAfterTaskEnds() throws Exception {
        var config=flags();
        config.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var events=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var terminated=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->{
            events.add("round");
            return completed(id);
        },memory,config,1,2,1)) {
            manager.setContinuousSession(new NativeWorkerJobManager.ContinuousSession() {
                @Override public void begin(long owner,UUID id) {
                    assertEquals(USER,owner);
                    assertEquals(task,id);
                    events.add("begin");
                }
                @Override public void end() {events.add("end");terminated.countDown();}
            });
            var job=manager.submitContinuous(USER,task,1,1,1,10);
            manager.await(USER,job,Duration.ofSeconds(3));
            assertTrue(terminated.await(3,TimeUnit.SECONDS));
            assertEquals(List.of("begin","round","end"),List.copyOf(events));
        }
    }

    @Test void partialWorkerSnapshotRetainsVerifiedProgress() {
        var properties=flags();
        properties.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        try(var manager=new NativeWorkerJobManager((u,t,rev,max)->completed(t),
                memory,properties,1,2,2)) {
            var job=manager.submitContinuous(USER,task,1,1,1,10);
            var snapshot=manager.await(USER,job,Duration.ofSeconds(2));
            assertEquals(NativeWorkerJobManager.State.PARTIAL,snapshot.state());
            assertEquals(8,snapshot.verifiedBytes());
            assertEquals(1,snapshot.committedPieces());
            assertEquals(8,manager.metrics(USER,job).verifiedBytes());
        }
    }

    @Test void boundedContinuousStopsAfterThreeStalledRoundsAndLeavesTaskPaused() {
        var config=flags();
        config.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var invocations=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            invocations.incrementAndGet();
            var current=memory.get(owner,id);
            assertEquals("READY",current.state());
            assertEquals(revision,current.revision());
            var paused=memory.pause(owner,id,revision);
            return new NativeTaskWorkerRoundService.RoundReport(1,false,
                    new PieceDownloadCoordinator.RoundResult(0,0,0,List.of(),0,0,0),
                    paused);
        },memory,config,1,2,2)) {
            var job=manager.submitContinuous(USER,task,1,1,12,10);
            var snapshot=manager.await(USER,job,Duration.ofSeconds(5));
            assertEquals(NativeWorkerJobManager.State.PARTIAL,snapshot.state());
            assertEquals(3,invocations.get(),"Three stalled rounds must bound retries");
            assertEquals(3,manager.metrics(USER,job).rounds());
            assertEquals(0,snapshot.verifiedBytes());
            assertEquals("PAUSED",memory.get(USER,task).state());
            assertTrue(snapshot.error().contains("No verified"));
            assertThrows(NoSuchElementException.class,()->manager.status(USER+1,job));
        }
    }

    @Test void disabledGatePreventsSubmissionAndCrossUserStatusIsHidden() {
        var flags=flags();
        flags.setAsyncWorkerEnabled(false);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        try(var manager=new NativeWorkerJobManager((u,t,v,p)->completed(t),
                memory,flags,1,2,2)) {
            assertThrows(IllegalStateException.class,()->manager.submit(USER,task,1,1));
            flags.setAsyncWorkerEnabled(true);
            var job=manager.submit(USER,task,1,1);
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,job,Duration.ofSeconds(1)).state());
            assertEquals(1,manager.status(USER,job).committedPieces());
            assertEquals(8,manager.status(USER,job).verifiedBytes());
            assertThrows(NoSuchElementException.class,()->manager.status(USER+1,job));
            assertThrows(NoSuchElementException.class,()->manager.cancel(USER+1,job));
            assertThrows(NoSuchElementException.class,
                    ()->manager.submit(USER+1,task,1,1));
            assertThrows(IllegalStateException.class,
                    ()->manager.submit(USER,task,2,1)); // still READY rev 1 in fake DB
        }
    }

    @Test void queuedCancellationNeverInvokesWorkerAndActiveDuplicateIsRejected() throws Exception {
        var memory=new InMemoryTasks();
        UUID waiting=UUID.randomUUID(),extra=UUID.randomUUID();
        memory.put(USER,task,"READY",1);
        memory.put(USER,waiting,"READY",1);
        memory.put(USER,extra,"READY",1);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger workerCalls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((u,t,rev,max)->{
                workerCalls.incrementAndGet();
                started.countDown();
                try {release.await(3,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                return completed(t);
            },memory,flags(),1,1,2)) {
            var first=manager.submit(USER,task,1,1);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            var second=manager.submit(USER,waiting,1,1);
            assertThrows(RejectedExecutionException.class,
                    ()->manager.submit(USER,waiting,1,1));
            assertThrows(RejectedExecutionException.class,
                    ()->manager.submit(USER,extra,1,1));
            var stopped=manager.cancel(USER,second);
            assertEquals(NativeWorkerJobManager.State.CANCELLED,stopped.state());
            assertEquals("PAUSED",memory.get(USER,waiting).state());
            release.countDown();
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,first,Duration.ofSeconds(2)).state());
            assertEquals(1,workerCalls.get());
        }finally {release.countDown();}
    }

    @Test void perUserCapacityAndGlobalQueueAreBounded() throws Exception {
        var memory=new InMemoryTasks();
        UUID second=UUID.randomUUID(),third=UUID.randomUUID();
        memory.put(USER,task,"READY",1);
        memory.put(USER,second,"READY",1);
        memory.put(USER+1,third,"READY",1);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((u,t,rev,max)->{
                started.countDown();
                try{release.await(2,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                return completed(t);
            },memory,flags(),1,1,1)) {
            manager.submit(USER,task,1,1);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class,
                    ()->manager.submit(USER,second,1,1));
            manager.submit(USER+1,third,1,1); // one global queued slot
            assertThrows(RejectedExecutionException.class,
                    ()->manager.submit(USER,second,1,1)); // queue still full
        }finally {release.countDown();}
    }

    @Test void saturatedGlobalQueueRejectsWithoutRetainingGhostJobsAndRecoversAfterDrain() throws Exception {
        var memory=new InMemoryTasks();
        UUID queued=UUID.randomUUID(), rejected=UUID.randomUUID();
        memory.put(USER,task,"READY",1);
        memory.put(USER+1,queued,"READY",1);
        memory.put(USER+2,rejected,"READY",1);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            calls.incrementAndGet();
            if(id.equals(task)) {
                entered.countDown();
                try {release.await(3,TimeUnit.SECONDS);}
                catch(InterruptedException e) {Thread.currentThread().interrupt();}
            }
            return completed(id);
        },memory,flags(),1,1,1)) {
            UUID running=manager.submit(USER,task,1,1);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            UUID waiting=manager.submit(USER+1,queued,1,1);
            for(int i=0;i<30;i++)
                assertThrows(RejectedExecutionException.class,
                        ()->manager.submit(USER+2,rejected,1,1));
            assertTrue(manager.latestOwnedTaskJob(USER+2,rejected).isEmpty(),
                    "Rejected requests must not leave phantom history entries");
            var health=manager.queueHealth();
            assertEquals(30,health.rejectedSubmissions());
            assertEquals(1,health.queueCapacity());
            assertEquals(1,health.queuedRunnables());
            assertEquals(1,health.executingJobs());
            assertEquals(2,health.trackedJobs());
            assertFalse(health.closed());
            assertEquals(NativeWorkerJobManager.State.QUEUED,
                    manager.status(USER+1,waiting).state());
            release.countDown();
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,running,Duration.ofSeconds(2)).state());
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER+1,waiting,Duration.ofSeconds(2)).state());
            UUID retried=manager.submit(USER+2,rejected,1,1);
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER+2,retried,Duration.ofSeconds(2)).state());
            assertEquals(3,calls.get(),"Rejected requests must never invoke a worker");
        }finally {release.countDown();}
    }

    @Test void cancelFencesRunningTaskAndLateReturnCannotOverwriteCancelled() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        CountDownLatch exited=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((u,t,rev,max)->{
                started.countDown();
                try {
                while(release.getCount()>0) {
                    try {release.await(100,TimeUnit.MILLISECONDS);}
                    catch(InterruptedException ignored) { /* simulate non-interruptible I/O */ }
                }
                if(!"READY".equals(memory.get(u,t).state()))
                    throw new IOException("Persistent CAS fence prevents any late writes");
                return completed(t);
                } finally { exited.countDown(); }
            },memory,flags(),1,2,2)) {
            var job=manager.submit(USER,task,1,1);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            assertEquals(NativeWorkerJobManager.State.RUNNING,manager.status(USER,job).state());
            var stopped=manager.cancel(USER,job);
            assertEquals(NativeWorkerJobManager.State.CANCELLED,stopped.state());
            assertEquals("PAUSED",memory.get(USER,task).state());
            assertEquals(2,memory.get(USER,task).revision());
            // A cancelled Future is "done" even while the underlying socket body
            // is still alive; never allow a second Worker onto that Checkpoint.
            memory.put(USER,task,"READY",3);
            assertThrows(RejectedExecutionException.class,
                    ()->manager.submit(USER,task,3,1));
            release.countDown();
            assertTrue(exited.await(1,TimeUnit.SECONDS));
            assertEquals("READY",memory.get(USER,task).state(),
                    "A late cancelled Worker must not pause the newly prepared task");
            assertEquals(NativeWorkerJobManager.State.CANCELLED,
                    manager.await(USER,job,Duration.ofSeconds(1)).state());
            assertEquals(0,manager.status(USER,job).verifiedBytes());
        }finally {release.countDown();}
    }

    @Test void browserReloadLooksUpOnlyCurrentOwnersJobAndRejectsForeignTaskIds()
            throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((u,t,rev,pieces)->{
            entered.countDown();
            try {release.await(2,TimeUnit.SECONDS);}
            catch(InterruptedException e) {Thread.currentThread().interrupt();}
            return completed(t);
        },memory,flags(),1,2,1)) {
            assertTrue(manager.latestOwnedTaskJob(USER,task).isEmpty());
            var jobId=manager.submit(USER,task,1,1);
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            var linked=manager.latestOwnedTaskJob(USER,task).orElseThrow();
            assertEquals(jobId,linked.jobId());
            assertEquals(NativeWorkerJobManager.State.RUNNING,linked.state());
            assertThrows(NoSuchElementException.class,
                    ()->manager.latestOwnedTaskJob(USER+1,task));
            assertThrows(NoSuchElementException.class,
                    ()->manager.latestOwnedTaskJob(USER,UUID.randomUUID()));
            release.countDown();
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,jobId,Duration.ofSeconds(2)).state());
            assertEquals(jobId,manager.latestOwnedTaskJob(USER,task).orElseThrow().jobId());
        }finally {release.countDown();}
    }

    @Test void cannotRecoverOrphanWhileAnyOtherTaskOwnsWorkerInThisJvm()
            throws Exception {
        var memory=new InMemoryTasks();
        UUID other=UUID.randomUUID();
        memory.put(USER,task,"RUNNING",7);
        memory.put(USER,other,"READY",1);
        var flags=flags();
        flags.setSingleInstanceOrphanRecoveryEnabled(true);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((user,id,revision,pieces)->{
            entered.countDown();
            try {release.await(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            return completed(id);
        },memory,flags,1,2,2)) {
            var job=manager.submit(USER,other,1,1);
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    ()->manager.recoverOwnedOrphan(USER,task,7),
                    "No orphan can be reclaimed while any local worker is active");
            assertEquals("RUNNING",memory.get(USER,task).state());
            release.countDown();
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,job,Duration.ofSeconds(2)).state());
            long drainedBy=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while((manager.queueHealth().executingJobs()>0
                    || manager.queueHealth().activeThreads()>0)
                    && System.nanoTime()<drainedBy)
                Thread.sleep(10);
            assertEquals(0,manager.queueHealth().executingJobs());
            assertEquals(0,manager.queueHealth().activeThreads(),
                    "Recovery must wait until the executor thread has actually returned");
            var recovered=manager.recoverOwnedOrphan(USER,task,7);
            assertEquals("PAUSED",recovered.state());
            assertEquals(8,recovered.revision());
        }finally {release.countDown();}
    }

    @Test void repeatedCancellationDoesNotReleaseSlotUntilStubbornWorkerExits() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var exited=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->{
            entered.countDown();
            try {
                while(release.getCount()>0) {
                    try {release.await(25,TimeUnit.MILLISECONDS);}
                    catch(InterruptedException ignored) { /* transport ignores interruption */ }
                }
                return completed(id);
            }finally {exited.countDown();}
        },memory,flags(),1,2,1)) {
            UUID first=manager.submit(USER,task,1,1);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<50;i++) {
                assertEquals(NativeWorkerJobManager.State.CANCELLED,
                        manager.cancel(USER,first).state());
                assertEquals("PAUSED",memory.get(USER,task).state());
                assertEquals(2,memory.get(USER,task).revision());
            }
            memory.put(USER,task,"READY",3);
            for(int i=0;i<20;i++) {
                assertThrows(RejectedExecutionException.class,
                        ()->manager.submit(USER,task,3,1));
            }
            assertThrows(NoSuchElementException.class,()->manager.cancel(USER+1,first));
            release.countDown();
            assertTrue(exited.await(2,TimeUnit.SECONDS));
            UUID second=null;
            for(int i=0;i<100 && second==null;i++) {
                try {second=manager.submit(USER,task,3,1);}
                catch(RejectedExecutionException e) {Thread.sleep(10);}
            }
            assertNotNull(second,"A new Worker can start after the original exits");
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    manager.await(USER,second,Duration.ofSeconds(2)).state());
            assertEquals(NativeWorkerJobManager.State.CANCELLED,manager.status(USER,first).state());
        }finally {release.countDown();}
    }

    @Test void continuousWorkerRetriesTransientIoTwiceWithoutChangingRevision() {
        var settings=flags();
        settings.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->{
            assertEquals(1,rev);
            if(calls.incrementAndGet()<3)throw new IOException("Transient Peer reset");
            return completed(id);
        },memory,settings,1,2,1)) {
            UUID job=manager.submitContinuous(USER,task,1,1,1,10);
            assertEquals(NativeWorkerJobManager.State.PARTIAL,
                    manager.await(USER,job,Duration.ofSeconds(3)).state());
            assertEquals(3,calls.get());
        }
    }

    @Test void continuousNetworkDeadlineStopsPartialAndPausesPersistentTask() {
        var settings=flags();
        settings.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->{
            calls.incrementAndGet();
            memory.put(owner,id,"RUNNING",rev+1);
            try {
                TimeUnit.NANOSECONDS.sleep(NativeNetworkBudget.cap(Duration.ofSeconds(20)).toNanos());
            }catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted deadline test",interrupted);
            }
            NativeNetworkBudget.check();
            throw new AssertionError("The continuous network deadline must have expired");
        },memory,settings,1,2,1)) {
            UUID job=manager.submitContinuous(USER,task,1,1,5,10);
            var snapshot=manager.await(USER,job,Duration.ofSeconds(15));
            assertEquals(NativeWorkerJobManager.State.PARTIAL,snapshot.state());
            assertEquals("Continuous native network deadline exhausted",snapshot.error());
            assertEquals("PAUSED",memory.get(USER,task).state());
            assertEquals(3,memory.get(USER,task).revision());
            assertEquals(1,calls.get());
        }
    }

    @Test void continuousWorkerDoesNotRetryAfterDurablePause() {
        var settings=flags();
        settings.setContinuousWorkerEnabled(true);
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var calls=new AtomicInteger();
        try(var manager=new NativeWorkerJobManager((owner,id,rev,pieces)->{
            calls.incrementAndGet();
            memory.pause(owner,id,rev);
            throw new IOException("Peer failed after task pause");
        },memory,settings,1,2,1)) {
            UUID job=manager.submitContinuous(USER,task,1,1,5,10);
            assertEquals(NativeWorkerJobManager.State.FAILED,
                    manager.await(USER,job,Duration.ofSeconds(2)).state());
            assertEquals(1,calls.get());
            assertEquals("PAUSED",memory.get(USER,task).state());
        }
    }

    @Test void failedWorkerFencesPersistentTaskAndRequiresExplicitPreparation() {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            throw new IOException("Simulated Peer disconnect");
        },memory,flags(),1,2,1)) {
            UUID job=manager.submit(USER,task,1,1);
            assertEquals(NativeWorkerJobManager.State.FAILED,
                    manager.await(USER,job,Duration.ofSeconds(2)).state());
            var paused=memory.get(USER,task);
            assertEquals("PAUSED",paused.state());
            assertEquals(2,paused.revision());
            assertThrows(IllegalStateException.class,
                    ()->manager.submit(USER,task,1,1));
            assertThrows(NoSuchElementException.class,
                    ()->manager.status(USER+1,job));
        }
    }

    @Test void shutdownFencesActiveWorkAndDiscardsLateResults() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var exited=new CountDownLatch(1);
        var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            entered.countDown();
            try {
                while(release.getCount()>0) {
                    try {release.await(20,TimeUnit.MILLISECONDS);}
                    catch(InterruptedException ignored) { /* emulate delayed transport close */ }
                }
                return completed(id);
            }finally {exited.countDown();}
        },memory,flags(),1,2,1);
        try {
            var job=manager.submit(USER,task,1,1);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            manager.close();
            assertEquals("PAUSED",memory.get(USER,task).state());
            assertEquals(2,memory.get(USER,task).revision());
            assertEquals(NativeWorkerJobManager.State.CANCELLED,manager.status(USER,job).state());
            release.countDown();
            assertTrue(exited.await(2,TimeUnit.SECONDS));
            assertEquals(NativeWorkerJobManager.State.CANCELLED,
                    manager.await(USER,job,Duration.ofSeconds(1)).state());
            assertEquals(0,manager.status(USER,job).verifiedBytes());
            assertThrows(IllegalStateException.class,
                    ()->manager.submit(USER,task,2,1));
        }finally {
            release.countDown();
            manager.close();
        }
    }

    @Test void repeatedManagerShutdownReapsWorkerThreadsWithoutAccumulation() throws Exception {
        java.util.function.IntSupplier workers=()->(int)Thread.getAllStackTraces().keySet()
                .stream().filter(t->t.isAlive() &&
                        t.getName().equals("clipfetch-native-piece-round")).count();
        int baseline=workers.getAsInt();
        var memory=new InMemoryTasks();
        for(int cycle=0;cycle<12;cycle++) {
            UUID id=UUID.randomUUID();
            memory.put(USER,id,"READY",1);
            try(var manager=new NativeWorkerJobManager((owner,taskId,revision,pieces)->
                    completed(taskId),memory,flags(),2,4,2)) {
                UUID job=manager.submit(USER,id,1,1);
                assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                        manager.await(USER,job,Duration.ofSeconds(2)).state());
            }
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(workers.getAsInt()>baseline && System.nanoTime()<deadline)
            Thread.sleep(20);
        assertTrue(workers.getAsInt()<=baseline,
                "Closing transient managers must not accumulate native worker threads");
    }

    @Test void explicitOrphanRecoveryRequiresConfirmedSingleInstanceAndNoLiveJobs() {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"RUNNING",7);
        var flags=flags();
        try(var manager=new NativeWorkerJobManager((u,t,r,p)->completed(t),
                memory,flags,1,1,1)) {
            assertThrows(IllegalStateException.class,
                    ()->manager.recoverOwnedOrphan(USER,task,7));
            flags.setSingleInstanceOrphanRecoveryEnabled(true);
            assertThrows(NoSuchElementException.class,
                    ()->manager.recoverOwnedOrphan(USER+1,task,7));
            assertThrows(IllegalStateException.class,
                    ()->manager.recoverOwnedOrphan(USER,task,6));
            var reclaimed=manager.recoverOwnedOrphan(USER,task,7);
            assertEquals("PAUSED",reclaimed.state());
            assertEquals(8,reclaimed.revision());
            assertThrows(IllegalStateException.class,
                    ()->manager.recoverOwnedOrphan(USER,task,7));
        }
    }

    @Test void cancelThenImmediateResubmitWaitsForWorkerTerminationAndEnqueuesCleanly() throws Exception {
        var memory=new InMemoryTasks();
        memory.put(USER,task,"READY",1);
        var started=new CountDownLatch(1);
        var unblock=new CountDownLatch(1);
        try(var manager=new NativeWorkerJobManager((owner,id,revision,pieces)->{
            started.countDown();
            try {unblock.await(2,TimeUnit.SECONDS);}
            catch(InterruptedException ignored) {}
            return completed(id);
        },memory,flags(),1,2,1)) {
            UUID job1=manager.submit(USER,task,1,1);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            var cancelled=manager.cancel(USER,job1);
            assertEquals(NativeWorkerJobManager.State.CANCELLED,cancelled.state());
            assertEquals("PAUSED",memory.get(USER,task).state());
            // Prepare the task again
            memory.prepare(USER,task,memory.get(USER,task).revision());
            unblock.countDown();
            // Immediate resubmit should succeed without "Task already has an active native job"
            UUID job2=manager.submit(USER,task,memory.get(USER,task).revision(),1);
            assertNotNull(job2);
            assertNotEquals(job1,job2);
            var finished=manager.await(USER,job2,Duration.ofSeconds(2));
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,finished.state());
        }finally {unblock.countDown();}
    }
}
