package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.util.UUID;

/**
 * Explicit bounded P4 worker adapter: CAS claim -> Piece/Checkpoint -> CAS progress.
 * One round never starts a background thread. The task is PAUSED after a partial round,
 * or COMPLETED once all selected bytes are durably verified.
 */
public final class NativeTaskRoundRunner {
    public record Execution(PieceDownloadCoordinator.RoundResult round,
                            NativeTaskLifecycleService.TaskState task) {}
    private final NativeTaskLifecycleService lifecycle;

    public NativeTaskRoundRunner(NativeTaskLifecycleService lifecycle) {
        if(lifecycle==null)throw new IllegalArgumentException("Missing task lifecycle");
        this.lifecycle=lifecycle;
    }

    /**
     * Instance-scoped fenced execution. The guard runs BEFORE any Piece commit.
     * Epoch and revision are rechecked on each coordinator activity.
     */
    public Execution runFenced(long owner,UUID taskId,long readyRevision,
                               VerifiedPieceCheckpoint checkpoint,
                               PieceDownloadCoordinator coordinator,int maxPieces,
                               String instanceId,java.util.function.BooleanSupplier healthy)
            throws IOException {
        if(checkpoint==null || coordinator==null || !coordinator.usesCheckpoint(checkpoint)
                || healthy==null || maxPieces<1 || maxPieces>256 ||
                instanceId==null || instanceId.isBlank())
            throw new IllegalArgumentException("Invalid fenced Piece round arguments");
        if(!healthy.getAsBoolean())
            throw new NativeInstanceLeaseUnavailableException(
                    "Native Worker instance heartbeat/lease is unhealthy; fenced round refused");
        var claimed=lifecycle.claimFencedWithDiskLock(owner,taskId,readyRevision,instanceId);
        long epoch=claimed.epoch();
        var revision=new java.util.concurrent.atomic.AtomicLong(claimed.task().revision());
        var wire=new java.util.concurrent.atomic.AtomicLong(claimed.task().wireBytes());
        // Network children may verify the fence while the owner commits a Piece.
        // Serialize DB CAS + revision publication against every guard read.
        var fenceLock=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
        // The DB-backed heartbeat was freshly checked at round admission.
        // Throttle ONLY that duplicate health query on 16KiB Block operations.
        // Epoch/revision checks and durable Piece commit CAS remain strict.
        var nextHeartbeatProbeNanos=new java.util.concurrent.atomic.AtomicLong(0);
        final long HEARTBEAT_PROBE_INTERVAL_NANOS=
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        PieceDownloadCoordinator.RoundResult result;
        try {
            result=coordinator.runRound(maxPieces,(piece,pieceSize,verified)->{
                fenceLock.writeLock().lock();
                try {
                    long transferred=wire.addAndGet(pieceSize);
                    var updated=lifecycle.syncFencedProgress(owner,taskId,revision.get(),
                            epoch,instanceId,checkpoint,transferred);
                    revision.set(updated.revision());
                }finally {fenceLock.writeLock().unlock();}
            },()->{
                fenceLock.readLock().lock();
                try {
                    if(checkpoint.isComplete())return;
                    long now=System.nanoTime();
                    long due=nextHeartbeatProbeNanos.get();
                    if((due==0 || now-due>=0) &&
                            nextHeartbeatProbeNanos.compareAndSet(
                                    due,now+HEARTBEAT_PROBE_INTERVAL_NANOS)) {
                        if(!healthy.getAsBoolean())
                            throw new NativeInstanceLeaseUnavailableException(
                                    "Native Worker instance heartbeat/lease lost during Piece transfer");
                    }
                    try {lifecycle.requireFence(owner,taskId,revision.get(),epoch,instanceId);}
                    catch(IllegalStateException ex) {throw new IOException("Native execution fence lost",ex);}
                }finally {fenceLock.readLock().unlock();}
            });
        }finally {
            try {
                var latest=lifecycle.get(owner,taskId);
                if("RUNNING".equals(latest.state()) && latest.revision()==revision.get()) {
                    lifecycle.pauseFencedWithDiskLock(owner,taskId,revision.get(),epoch,instanceId);
                }
            }catch(java.util.NoSuchElementException | IllegalStateException ignored) {}
        }
        return new Execution(result,lifecycle.get(owner,taskId));
    }

    public Execution run(long owner,UUID taskId,long readyRevision,
                         VerifiedPieceCheckpoint checkpoint,
                         PieceDownloadCoordinator coordinator,int maxPieces) throws IOException {
        if(checkpoint==null || coordinator==null || !coordinator.usesCheckpoint(checkpoint)
                || maxPieces<1 || maxPieces>256)
            throw new IllegalArgumentException("Missing Piece round dependencies");
        var current=lifecycle.get(owner,taskId);
        if(!"READY".equals(current.state()) || current.revision()!=readyRevision)
            throw new IllegalStateException("Task must be READY at requested revision");
        // Coordinator is prepared before acquiring Worker ownership. Download starts
        // only after CAS succeeds.
        var claimed=lifecycle.claimForWorker(owner,taskId,readyRevision);
        java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong(claimed.revision());
        java.util.concurrent.atomic.AtomicLong wire=new java.util.concurrent.atomic.AtomicLong(claimed.wireBytes());
        var fenceLock=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
        PieceDownloadCoordinator.RoundResult result;
        try {
            result=coordinator.runRound(maxPieces,(piece,pieceSize,verified)->{
                fenceLock.writeLock().lock();
                try {
                    long transferred=wire.addAndGet(pieceSize);
                    var updated=lifecycle.syncRunningProgress(
                            owner,taskId,revision.get(),checkpoint,transferred);
                    revision.set(updated.revision());
                }finally {fenceLock.writeLock().unlock();}
            },()->{
                fenceLock.readLock().lock();
                try {
                    var active=lifecycle.get(owner,taskId);
                    if(!"RUNNING".equals(active.state()) || active.revision()!=revision.get())
                        throw new IOException("Native Worker lost its active task revision");
                }finally {fenceLock.readLock().unlock();}
            });
        }finally {
            // A partial bounded round does not leave a zombie RUNNING row.
            // If a different thread has already paused/deleted/fenced the task,
            // its revision wins and no stale Worker mutation is attempted.
            try {
                var latest=lifecycle.get(owner,taskId);
                if("RUNNING".equals(latest.state()) && latest.revision()==revision.get())
                    lifecycle.pause(owner,taskId,latest.revision());
            }catch(java.util.NoSuchElementException | IllegalStateException ignored) {
                // Concurrent owner action or an already-fenced Worker wins.
            }
        }
        return new Execution(result,lifecycle.get(owner,taskId));
    }
}
