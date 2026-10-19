package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskPeerActorPoolTest {
    @Test void reusesActualActorThreadAndExecutorAcrossRoundsButNotAcrossOwners() throws Exception {
        var actors=new TaskPeerActorPool();
        Thread owner=Thread.currentThread();
        var task=UUID.randomUUID();
        actors.begin(owner,9,task,"a".repeat(40));
        try {
            var first=actors.borrow(owner,1);
            Thread threadA=first.submit(Thread::currentThread).get(2,TimeUnit.SECONDS);
            var second=actors.borrow(owner,1);
            assertSame(first,second,"The task must retain the same executor between rounds");
            assertSame(threadA,second.submit(Thread::currentThread).get(2,TimeUnit.SECONDS),
                    "A stable single actor should retain its execution thread");
            var next=actors.borrow(owner,8);
            assertSame(first,next);
            assertTrue(next.getCorePoolSize()>=8);
            assertTrue(actors.active(owner));
            assertThrows(IllegalStateException.class,
                    ()->actors.begin(owner,10,UUID.randomUUID(),"b".repeat(40)));
        }finally {
            actors.close(owner);
        }
        assertFalse(actors.active(owner));
        assertNull(actors.borrow(owner,1));
    }
    @Test void rediscoveryGrowthNeverLowersMaximumBelowCoreFromPreviousRound()
            throws Exception {
        var actors=new TaskPeerActorPool();
        Thread owner=Thread.currentThread();
        actors.begin(owner,42,UUID.randomUUID(),"a".repeat(40));
        try {
            // Exact production regression: previous round retained 64 actors.
            ThreadPoolExecutor executor=actors.borrow(owner,64);
            assertEquals(64,executor.getCorePoolSize());
            assertEquals(64,executor.getMaximumPoolSize());

            // Current round admits only 8 worker slots but must not tear down
            // established actor threads and their task-owned TCP affinity.
            assertSame(executor,actors.borrow(owner,8));
            assertEquals(64,executor.getCorePoolSize());

            // Dynamic Peer refresh then requests 16. The old coordinator
            // called setMaximumPoolSize(16) and threw IllegalArgumentException.
            assertDoesNotThrow(()->TaskPeerActorPool.ensureCapacity(executor,16));
            assertEquals(64,executor.getCorePoolSize());
            assertEquals(64,executor.getMaximumPoolSize());
            assertEquals(42,executor.submit(()->42).get(2,TimeUnit.SECONDS));

            // A later round may legitimately expand past the previous peak.
            TaskPeerActorPool.ensureCapacity(executor,96);
            assertEquals(96,executor.getCorePoolSize());
            assertEquals(96,executor.getMaximumPoolSize());
            assertSame(executor,actors.borrow(owner,4));
            TaskPeerActorPool.ensureCapacity(executor,12);
            assertEquals(96,executor.getCorePoolSize());
            assertEquals(96,executor.getMaximumPoolSize());
        }finally {actors.close(owner);}
    }

    @Test void rediscoveryAndBorrowUseOneConsistentCapacityLock() throws Exception {
        var actors=new TaskPeerActorPool();
        Thread owner=Thread.currentThread();
        actors.begin(owner,44,UUID.randomUUID(),"b".repeat(40));
        ExecutorService updates=Executors.newFixedThreadPool(2);
        try {
            var executor=actors.borrow(owner,64);
            var go=new CountDownLatch(1);
            Future<?> refresh=updates.submit(()->{
                try {go.await();}
                catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                for(int i=0;i<100;i++)
                    TaskPeerActorPool.ensureCapacity(executor,i%2==0?16:80);
            });
            Future<?> another=updates.submit(()->{
                try {go.await();}
                catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                for(int i=0;i<100;i++)
                    TaskPeerActorPool.ensureCapacity(executor,i%2==0?8:32);
            });
            go.countDown();
            refresh.get(4,TimeUnit.SECONDS);
            another.get(4,TimeUnit.SECONDS);
            assertTrue(executor.getMaximumPoolSize()>=executor.getCorePoolSize());
            assertEquals(80,executor.getCorePoolSize());
            assertEquals(80,executor.getMaximumPoolSize());
        }finally {updates.shutdownNow();actors.close(owner);}
    }

    @Test void scopedShutdownCancelsOwnedWorkersWithoutTouchingOtherTasks() throws Exception {
        var actors=new TaskPeerActorPool();
        var first=Thread.currentThread();
        var other=new Thread(()->{});
        actors.begin(first,1,UUID.randomUUID(),"c".repeat(40));
        actors.begin(other,2,UUID.randomUUID(),"d".repeat(40));
        var a=actors.borrow(first,1);
        var b=actors.borrow(other,1);
        actors.close(first);
        assertTrue(a.isShutdown());
        assertFalse(b.isShutdown());
        actors.close(other);
        assertTrue(b.isShutdown());
    }
}
