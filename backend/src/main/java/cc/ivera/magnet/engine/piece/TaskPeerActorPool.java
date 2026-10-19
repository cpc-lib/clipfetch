package cc.ivera.magnet.engine.piece;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded, user/task-scoped group of long-lived Peer worker actors.
 * Actor threads retain their identity across Piece rounds so their TCP
 * affinity, Peer observations and mailboxes can be reused.
 *
 * Worker-owned round fencing, Piece validation and DB CAS remain unchanged.
 * A pool is never shared across user, task or infohash boundaries.
 */
final class TaskPeerActorPool {
    private record Scope(long user,UUID task,String hash,ThreadPoolExecutor workers) {}
    private final Map<Thread,Scope> tasks=new ConcurrentHashMap<>();
    static final int HARD_MAX=256;
    void begin(Thread owner,long user,UUID task,String hash) {
        if(owner==null || user<=0 || task==null || hash==null || hash.isBlank())
            throw new IllegalArgumentException("Invalid Peer actor scope");
        AtomicInteger numbering=new AtomicInteger();
        ThreadPoolExecutor executor=new ThreadPoolExecutor(
                1,1,30,TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),r->{
                    Thread thread=new Thread(null,r,
                            "clipfetch-task-peer-actor-"+numbering.incrementAndGet(),
                            0,false);
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        var inserted=new Scope(user,task,hash,executor);
        if(tasks.putIfAbsent(owner,inserted)!=null) {
            executor.shutdownNow();
            throw new IllegalStateException("Peer actor scope already exists");
        }
    }
    ThreadPoolExecutor borrow(Thread owner,int requested) {
        Scope scope=tasks.get(owner);
        if(scope==null)return null; // noncontinuous download keeps its legacy round executor
        if(requested<1 || requested>HARD_MAX)
            throw new IllegalArgumentException("Invalid actor admission");
        var executor=scope.workers;
        if(executor.isShutdown())throw new IllegalStateException("Peer actors have stopped");
        ensureCapacity(executor,requested);
        return executor;
    }
    /**
     * Dynamically discovered Peers may increase this round's desired slots
     * even when an earlier round already raised the persistent actor pool
     * beyond that value (e.g. core=64, round starts at 8, refresh asks 16).
     * Never lower maximumPoolSize below the retained corePoolSize.
     *
     * Keep both changes atomic relative to borrowing the same task pool;
     * the executor is deliberately never shrunk until the task ends.
     */
    static void ensureCapacity(ThreadPoolExecutor executor,int requested) {
        if(executor==null || requested<1 || requested>HARD_MAX)
            throw new IllegalArgumentException("Invalid Peer actor capacity");
        synchronized(executor) {
            if(executor.isShutdown())
                throw new IllegalStateException("Peer actor pool has stopped");
            int targetCore=Math.max(executor.getCorePoolSize(),requested);
            int targetMaximum=Math.max(executor.getMaximumPoolSize(),targetCore);
            if(targetMaximum!=executor.getMaximumPoolSize())
                executor.setMaximumPoolSize(targetMaximum);
            if(targetCore!=executor.getCorePoolSize())
                executor.setCorePoolSize(targetCore);
        }
    }
    boolean active(Thread owner) {return tasks.containsKey(owner);}
    void close(Thread owner) {
        Scope scope=tasks.remove(owner);
        if(scope!=null)scope.workers.shutdownNow();
    }
    void abort(Thread owner) {
        Scope scope=tasks.get(owner);
        if(scope!=null)scope.workers.shutdownNow();
    }
}
