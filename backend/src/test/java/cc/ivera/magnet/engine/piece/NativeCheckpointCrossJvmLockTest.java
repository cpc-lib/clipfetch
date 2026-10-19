package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeCheckpointCrossJvmLockTest {
    @TempDir Path temp;

    /** Independent child JVM holds the same lock as a checkpoint writer. */
    public static class LockHolder {
        public static void main(String[] args) throws Exception {
            Path dir=Path.of(args[0]);
            Path marker=Path.of(args[1]);
            Path lock=dir.resolve(".clipfetch-native-state").resolve("piece-commit.lock");
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var held=channel.lock()) {
                Files.writeString(marker,"LOCKED");
                Thread.sleep(60000);
            }
        }
    }

    @Test void interruptedCleanupWaitsForExistingPieceWriterThenRestoresInterrupt()
            throws Exception {
        Path state=temp.resolve(".clipfetch-native-state");
        Files.createDirectories(state);
        var lockPath=state.resolve("piece-commit.lock");
        var invoked=new java.util.concurrent.atomic.AtomicBoolean();
        var interruptRestored=new java.util.concurrent.atomic.AtomicBoolean();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var started=new java.util.concurrent.CountDownLatch(1);
        try(var channel=FileChannel.open(lockPath,
                StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var held=channel.lock()) {
            Thread worker=new Thread(()->{
                try {
                    started.countDown();
                    NativeCheckpointOwnershipLock.executeForCleanup(temp,()->{
                        invoked.set(true);
                        assertFalse(Thread.currentThread().isInterrupted(),
                                "Keep lock acquisition separate from cancellation signal");
                        return true;
                    });
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }catch(Throwable ex) {failure.set(ex);}
                finally {Thread.interrupted();}
            },"native-fenced-cleanup-waiter");
            worker.start();
            assertTrue(started.await(2,TimeUnit.SECONDS));
            Thread.sleep(75);
            worker.interrupt(); // A second cancellation while Piece commit holds the lock.
            Thread.sleep(75);
            assertFalse(invoked.get(),"Must not bypass the outstanding disk Piece lock");
            held.release();
            worker.join(3000);
            assertFalse(worker.isAlive(),"Cleanup must remain bounded");
        }
        assertNull(failure.get(),"Interrupted cleanup should acquire after writer exits");
        assertTrue(invoked.get());
        assertTrue(interruptRestored.get(),"Original interrupt must be observable by caller");
    }

    @Test void ordinaryOwnershipAcquisitionRemainsInterruptible() throws Exception {
        Files.createDirectories(temp.resolve(".clipfetch-native-state"));
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{
            Thread.currentThread().interrupt();
            try {NativeCheckpointOwnershipLock.execute(temp,()->true);}
            catch(Throwable ex) {failure.set(ex);}
            finally {Thread.interrupted();}
        });
        worker.start();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertInstanceOf(java.io.IOException.class,failure.get());
        assertTrue(failure.get().getMessage().contains("Ownership lock interrupted"));
    }

    @Test void childJvmCrashReleasesLockAndNewOwnerCanEnter() throws Exception {
        Path state=temp.resolve(".clipfetch-native-state");
        Files.createDirectories(state);
        Path marker=temp.resolve("child-ready");
        String exe=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        var command=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),
                LockHolder.class.getName(),temp.toString(),marker.toString())
                .redirectErrorStream(true).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(!Files.exists(marker) && command.isAlive() && System.nanoTime()<deadline)
                Thread.sleep(20);
            assertTrue(Files.exists(marker),"Child must acquire the file lock first");
            assertThrows(java.io.IOException.class,()->NativeCheckpointOwnershipLock.execute(temp,()->true));
            command.destroyForcibly();
            assertTrue(command.waitFor(5,TimeUnit.SECONDS));
            assertTrue(NativeCheckpointOwnershipLock.execute(temp,()->true));
        }finally {
            command.destroyForcibly();
            command.waitFor(5,TimeUnit.SECONDS);
        }
    }
}
