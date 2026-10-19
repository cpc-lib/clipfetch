package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Cooperative cross-JVM exclusion for checkpoint writes and execution ownership changes. */
public final class NativeCheckpointOwnershipLock {
    private NativeCheckpointOwnershipLock() {}
    @FunctionalInterface public interface Action<T> { T run() throws IOException; }

    static boolean isNetworkFileStore(String type) {
        if(type==null)return true;
        String value=type.toLowerCase(java.util.Locale.ROOT);
        return value.contains("nfs") || value.contains("cifs") || value.contains("smb")
                || value.contains("sshfs") || value.contains("afs")
                || value.contains("9p") || value.contains("davfs");
    }

    static boolean isRemotePath(Path path) {
        if(path==null)return true;
        String raw=path.toString().replace('/', '\\');
        return raw.startsWith("\\\\");
    }

    static void requireLocalStorage(Path path) throws IOException {
        if(isRemotePath(path))
            throw new IOException("Native ownership rejects UNC/network storage");
        if(isNetworkFileStore(Files.getFileStore(path).type()))
            throw new IOException("Native ownership requires verified local filesystem");
    }

    public static <T> T execute(Path taskRoot,Action<T> action) throws IOException {
        return executeInternal(taskRoot,action,false,null);
    }

    /**
     * A cancelled fenced Worker must still release RUNNING ownership under the
     * same disk lock as Piece commits. Ignore interrupts ONLY during this bounded
     * safety cleanup; restore the interrupt flag before returning to the Worker.
     * Regular ownership operations remain interruptible via execute().
     */
    static <T> T executeForCleanup(Path taskRoot,Action<T> action) throws IOException {
        boolean[] interrupted={Thread.interrupted()};
        try {
            return executeInternal(taskRoot,action,true,interrupted);
        }finally {
            if(Thread.interrupted() || interrupted[0])
                Thread.currentThread().interrupt();
        }
    }

    private static <T> T executeInternal(Path taskRoot,Action<T> action,
                                         boolean cleanup,boolean[] interrupted) throws IOException {
        if(taskRoot==null || action==null)throw new IllegalArgumentException("Missing checkpoint ownership lock inputs");
        Path state=taskRoot.toAbsolutePath().normalize().resolve(".clipfetch-native-state");
        if(!Files.isDirectory(state,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(state))
            throw new IOException("Native checkpoint directory unavailable");
        // Java FileLock on remote mounts does not prove cross-host durability or exclusion.
        // Refuse known network-backed file stores until a distributed storage protocol is verified.
        requireLocalStorage(state);
        Path lockFile=state.resolve("piece-commit.lock");
        if(Files.isSymbolicLink(lockFile))throw new IOException("Unsafe checkpoint lock");
        try(var channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            FileLock acquired=null;
            while(acquired==null) {
                if(Thread.currentThread().isInterrupted()) {
                    if(!cleanup)throw new IOException("Ownership lock interrupted");
                    if(Thread.interrupted())interrupted[0]=true;
                }
                try {acquired=channel.tryLock();}
                catch(OverlappingFileLockException busy) {}
                if(acquired==null) {
                    if(System.nanoTime()>=deadline)throw new IOException("Ownership lock timed out");
                    try {Thread.sleep(25);}
                    catch(InterruptedException e) {
                        if(cleanup)interrupted[0]=true;
                        else {
                            Thread.currentThread().interrupt();
                            throw new IOException("Ownership lock interrupted",e);
                        }
                    }
                }
            }
            try(FileLock held=acquired) {return action.run();}
        }
    }
}
