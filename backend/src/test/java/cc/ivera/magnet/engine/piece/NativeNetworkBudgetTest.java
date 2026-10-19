package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NativeNetworkBudgetTest {
    @Test void nestedDeadlineNeverExtendsParentAndExpiredRequestsFail() throws Exception {
        assertFalse(NativeNetworkBudget.active());
        try(var parent=NativeNetworkBudget.open(Duration.ofMillis(80))) {
            Duration before=NativeNetworkBudget.cap(Duration.ofSeconds(10));
            assertTrue(before.toMillis()<100);
            try(var nested=NativeNetworkBudget.open(Duration.ofSeconds(10))) {
                Duration inside=NativeNetworkBudget.cap(Duration.ofSeconds(10));
                assertTrue(inside.compareTo(before)<=0);
                Thread.sleep(90);
                assertThrows(SocketTimeoutException.class,NativeNetworkBudget::check);
                assertTrue(NativeNetworkBudget.exhausted());
            }
            assertThrows(SocketTimeoutException.class,NativeNetworkBudget::check);
        }
        assertFalse(NativeNetworkBudget.active());
        assertEquals(Duration.ofSeconds(2),NativeNetworkBudget.cap(Duration.ofSeconds(2)));
    }

    @Test void concurrentPieceThreadInheritsExactlyTheSameAbsoluteDeadline() throws Exception {
        try(var scoped=NativeNetworkBudget.open(Duration.ofMillis(75))) {
            var started=new CountDownLatch(1);
            var result=new AtomicReference<Throwable>();
            Thread child=new Thread(()->{
                try {
                    started.countDown();
                    Thread.sleep(90);
                    NativeNetworkBudget.check();
                    result.set(new AssertionError("Child extended deadline"));
                }catch(Throwable e) {result.set(e);}
            },"inherited-native-budget-child");
            child.start();
            assertTrue(started.await(1,TimeUnit.SECONDS));
            child.join(1500);
            assertFalse(child.isAlive());
            assertInstanceOf(SocketTimeoutException.class,result.get());
        }
        assertFalse(NativeNetworkBudget.active());
    }

    @Test void interruptionRejectsFurtherNetworkEvenBeforeTimeExpires() throws Exception {
        var worker=new AtomicReference<Throwable>();
        Thread task=new Thread(()->{
            try(var scope=NativeNetworkBudget.open(Duration.ofSeconds(5))) {
                Thread.currentThread().interrupt();
                NativeNetworkBudget.check();
                worker.set(new AssertionError("Interrupted Worker used network"));
            }catch(Throwable e){worker.set(e);}
        });
        task.start();task.join(1000);
        assertFalse(task.isAlive());
        assertInstanceOf(IOException.class,worker.get());
    }

    @Test void bindPropagatesDeadlineToNonInheritingPooledThreadAndCleansUpAfterwards() throws Exception {
        var executor=Executors.newSingleThreadExecutor(r->new Thread(null,r,"pooled-budget-test",0,false));
        try {
            // Unbound thread starts clean
            Future<Boolean> initialActive=executor.submit(NativeNetworkBudget::active);
            assertFalse(initialActive.get(1,TimeUnit.SECONDS));

            // Bind an active deadline
            long activeDeadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            Future<Duration> capped=executor.submit(()->{
                try(var scope=NativeNetworkBudget.bind(activeDeadline)) {
                    assertTrue(NativeNetworkBudget.active());
                    assertEquals(activeDeadline,NativeNetworkBudget.currentDeadline());
                    return NativeNetworkBudget.cap(Duration.ofSeconds(20));
                }
            });
            assertTrue(capped.get(1,TimeUnit.SECONDS).toMillis()<=10_100);

            // After task finishes, thread local is cleanly removed from the pooled thread
            Future<Boolean> afterActive=executor.submit(NativeNetworkBudget::active);
            assertFalse(afterActive.get(1,TimeUnit.SECONDS));

            // Binding an expired deadline immediately triggers DEADLINE_EXCEEDED
            long expiredDeadline=System.nanoTime()-Duration.ofMillis(100).toNanos();
            Future<Throwable> expiredResult=executor.submit(()->{
                try(var scope=NativeNetworkBudget.bind(expiredDeadline)) {
                    NativeNetworkBudget.check();
                    return null;
                }catch(Throwable e){return e;}
            });
            Throwable thrown=expiredResult.get(1,TimeUnit.SECONDS);
            assertInstanceOf(SocketTimeoutException.class,thrown);
            assertTrue(NativeNetworkBudget.deadlineExceeded(thrown));
        }finally {
            executor.shutdownNow();
        }
    }
}
