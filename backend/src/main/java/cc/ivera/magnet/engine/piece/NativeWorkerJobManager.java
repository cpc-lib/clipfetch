package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Single-JVM, opt-in queue for short Native Piece rounds (NOT a production download daemon).
 * Each submitted task has one active Job. Bounded threads, queue, per-user admission,
 * database CAS cancellation, ownership-aware status and explicit recovery fencing.
 *
 * Interrupt does not guarantee immediate termination of blocking socket reads:
 * the persisted PAUSED revision, not Thread.interrupt(), is the authority.
 */
@Slf4j
public final class NativeWorkerJobManager implements AutoCloseable {
    @FunctionalInterface
    public interface RoundInvoker {
        NativeTaskWorkerRoundService.RoundReport run(long owner,UUID taskId,
                                                     long revision,int maxPieces) throws IOException;
    }
    public interface ContinuousSession {
        void begin(long owner,UUID task) throws IOException;
        void end();
    }
    /** Read-only, owner-scoped process-local transfer telemetry. */
    @FunctionalInterface public interface NetworkTelemetry {
        long receivedPayloadBytes(long owner,UUID task);
    }
    @FunctionalInterface public interface PeerTelemetry {
        NativeTaskWorkerRoundService.PeerStatus status(long owner,UUID task);
    }
    private volatile PeerTelemetry peerTelemetry=
            (owner,task)->new NativeTaskWorkerRoundService.PeerStatus(0,0,0,0);
    public void setPeerTelemetry(PeerTelemetry telemetry) {
        peerTelemetry=Objects.requireNonNull(telemetry);
    }
    @FunctionalInterface public interface StagedTelemetry {
        NativeTaskWorkerRoundService.StagedProgress progress(long owner,UUID task);
    }
    @FunctionalInterface public interface ConnectionTelemetry {
        PeerPieceDownloadSession.TaskConnectionStats stats(long owner,UUID task);
    }
    private volatile StagedTelemetry stagedTelemetry=
            (owner,task)->new NativeTaskWorkerRoundService.StagedProgress(0,0);
    private volatile ConnectionTelemetry connectionTelemetry=
            (owner,task)->new PeerPieceDownloadSession.TaskConnectionStats(0,0,0,0,0,0,0,0);
    public void setStagedTelemetry(StagedTelemetry provider) {
        stagedTelemetry=Objects.requireNonNull(provider);
    }
    public void setConnectionTelemetry(ConnectionTelemetry provider) {
        connectionTelemetry=Objects.requireNonNull(provider);
    }
    private volatile NetworkTelemetry networkTelemetry=(owner,task)->0L;
    public void setNetworkTelemetry(NetworkTelemetry telemetry) {
        this.networkTelemetry=Objects.requireNonNull(telemetry);
    }
    private volatile ContinuousSession continuousSession;
    public void setContinuousSession(ContinuousSession session) {
        this.continuousSession=Objects.requireNonNull(session);
    }
    @FunctionalInterface
    public interface ActiveIoCanceller {
        void abort(Thread workerThread);
    }
    public interface TaskAccess {
        NativeTaskLifecycleService.TaskState get(long owner,UUID taskId);
        NativeTaskLifecycleService.TaskState pause(long owner,UUID taskId,long revision);
        default NativeTaskLifecycleService.TaskState prepare(long owner,UUID taskId,long revision) {
            throw new UnsupportedOperationException("Continuous task preparation is not configured");
        }
        NativeTaskLifecycleService.TaskState fenceOrphan(long owner,UUID taskId,long revision);
    }
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, PARTIAL }
    public record Snapshot(UUID jobId,UUID taskUuid,State state,int candidatePeers,
                           int committedPieces,long verifiedBytes,String error,Instant createdAt) {
        public boolean terminal() {
            return state==State.SUCCEEDED || state==State.FAILED || state==State.CANCELLED || state==State.PARTIAL;
        }
    }
    public record RequestQueueMetrics(long sentBlocks,long acknowledgedBlocks,
                                      int outstandingBlocks,int capacityBlocks,
                                      int occupancyPercent,long rttP50Millis,long rttP95Millis) {
        static RequestQueueMetrics empty(){return new RequestQueueMetrics(0,0,0,0,0,0,0);}
    }
    @FunctionalInterface public interface RequestQueueTelemetry {
        NativePeerRequestTelemetry.Snapshot stats(long owner,UUID task);
    }
    private volatile RequestQueueTelemetry requestQueueTelemetry=
            (owner,task)->new NativePeerRequestTelemetry().snapshot();
    public void setRequestQueueTelemetry(RequestQueueTelemetry telemetry){
        requestQueueTelemetry=Objects.requireNonNull(telemetry);
    }
    public record Metrics(UUID jobId,UUID taskUuid,State state,int rounds,
                          int committedPieces,long verifiedBytes,long selectedBytes,
                          long wireBytes,double recentBytesPerSecond,
                          double averageBytesPerSecond,long elapsedMillis,
                          Instant createdAt,long receivedPayloadBytes,
                          double networkBytesPerSecond,int peerPoolCount,
                          int verifiedPeerCount,int degradedPeerCount,int peerRefreshes,
                          int stagedPieces,long stagedVerifiedBytes,
                          long openedPeerSockets,long reusedPeerSockets,
                          long crossRoundPeerReuses,int idlePeerSockets,
                          int targetPeerActors,int activePeerActors,
                          long actorPeerReassignments,long retiredPeerSockets,
                          RequestQueueMetrics peerRequestQueue) {
        // Keep older controller and test constructor contracts unchanged.
        public Metrics(UUID jobId,UUID taskUuid,State state,int rounds,
                       int committedPieces,long verifiedBytes,long selectedBytes,
                       long wireBytes,double recentBytesPerSecond,
                       double averageBytesPerSecond,long elapsedMillis,
                       Instant createdAt,long receivedPayloadBytes,
                       double networkBytesPerSecond,int peerPoolCount,
                       int verifiedPeerCount,int degradedPeerCount,int peerRefreshes,
                       int stagedPieces,long stagedVerifiedBytes,
                       long openedPeerSockets,long reusedPeerSockets,
                       long crossRoundPeerReuses,int idlePeerSockets) {
            this(jobId,taskUuid,state,rounds,committedPieces,verifiedBytes,selectedBytes,
                    wireBytes,recentBytesPerSecond,averageBytesPerSecond,elapsedMillis,
                    createdAt,receivedPayloadBytes,networkBytesPerSecond,peerPoolCount,
                    verifiedPeerCount,degradedPeerCount,peerRefreshes,stagedPieces,
                    stagedVerifiedBytes,openedPeerSockets,reusedPeerSockets,
                    crossRoundPeerReuses,idlePeerSockets,0,0,0,0,RequestQueueMetrics.empty());
        }
        // Keep the existing metrics contract usable by controllers and tests.
        public Metrics(UUID jobId,UUID taskUuid,State state,int rounds,
                       int committedPieces,long verifiedBytes,long selectedBytes,
                       long wireBytes,double recentBytesPerSecond,
                       double averageBytesPerSecond,long elapsedMillis,Instant createdAt) {
            this(jobId,taskUuid,state,rounds,committedPieces,verifiedBytes,selectedBytes,
                    wireBytes,recentBytesPerSecond,averageBytesPerSecond,elapsedMillis,
                    createdAt,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,RequestQueueMetrics.empty());
        }
    }
    /** Bounded, aggregated process-local queue diagnostics; never exposes users or task identifiers. */
    public record QueueHealth(int activeThreads,int poolThreads,int queuedRunnables,
                              int executingJobs,int trackedJobs,int maxThreads,int queueCapacity,
                              long rejectedSubmissions,boolean closed) {}
    private long rejectedSubmissions;
    public synchronized QueueHealth queueHealth() {
        return new QueueHealth(executor.getActiveCount(),executor.getPoolSize(),
                executor.getQueue().size(),
                (int)jobs.values().stream().filter(j->j.executing).count(),
                jobs.size(),executor.getMaximumPoolSize(),
                executor.getQueue().size()+executor.getQueue().remainingCapacity(),
                rejectedSubmissions,closed);
    }
    /** Owner-scoped diagnostics safe for authenticated users. No global counts or peer data. */
    public record OwnerQueueHealth(int queuedJobs,int runningJobs,int executingJobs,
                                   int trackedJobs) {}
    public synchronized OwnerQueueHealth ownerQueueHealth(long owner) {
        if(owner<=0)throw new NoSuchElementException("Native queue unavailable");
        int queued=0,running=0,executing=0,tracked=0;
        for(Entry entry:jobs.values()) {
            if(entry.owner!=owner)continue;
            tracked++;
            if(entry.state==State.QUEUED)queued++;
            if(entry.state==State.RUNNING)running++;
            if(entry.executing)executing++;
        }
        return new OwnerQueueHealth(queued,running,executing,tracked);
    }
    private static final int MAX_HISTORY=256;

    static boolean shouldRetryContinuousFailure(IOException failure) {
        return !(failure instanceof SocketTimeoutException);
    }

    static long boundedContinuousBackoffMillis(int retryFailures,
                                               int stalledRounds,
                                               long remainingNanos) {
        if(remainingNanos<=0)return 0;
        long retryDelay=saturatedExponentialBackoffMillis(200,retryFailures);
        long stalledDelay=saturatedExponentialBackoffMillis(400,stalledRounds);
        long remainingMillis=TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return Math.min(Math.max(retryDelay,stalledDelay),remainingMillis);
    }

    private static long saturatedExponentialBackoffMillis(long base,int occurrences) {
        if(occurrences<=0)return 0;
        long safeShiftLimit=63-Long.numberOfLeadingZeros(Long.MAX_VALUE/base);
        int shift=occurrences-1;
        if(shift>safeShiftLimit)return Long.MAX_VALUE;
        return base<<shift;
    }

    private final RoundInvoker invoker;
    private final TaskAccess tasks;
    private final ActiveIoCanceller ioCanceller;
    private final NativeTorrentProperties flags;
    private volatile java.util.function.BooleanSupplier instanceAdmissionHealthy = () -> false;

    /** Set during Spring bean construction; defaults fail-closed when heartbeat is enabled. */
    public synchronized void setInstanceAdmissionHealthy(java.util.function.BooleanSupplier guard) {
        if(guard==null)throw new IllegalArgumentException("Missing native instance admission guard");
        this.instanceAdmissionHealthy=guard;
    }
    private final int perUserLimit;
    private final ThreadPoolExecutor executor;
    private final Map<UUID,Entry> jobs=new LinkedHashMap<>();
    private boolean closed;

    private static final class Entry {
        final UUID jobId=UUID.randomUUID();
        final UUID task;
        final long owner;
        final Instant createdAt=Instant.now();
        final long startedNanos=System.nanoTime();
        final int maxRounds;
        final int maxSeconds;
        final int piecesPerRound;
        volatile int rounds;
        volatile int committed;
        volatile int candidatePeers;
        volatile long verified;
        volatile long initialVerified;
        volatile long selected;
        volatile long wire;
        volatile double recentSpeed;
        volatile long lastSampleNanos;
        volatile long receivedPayloadBytes;
        volatile long lastNetworkSampleNanos;
        volatile double networkSpeed;
        final NativeNetworkRateMeter networkRateMeter=new NativeNetworkRateMeter();
        volatile State state=State.QUEUED;
        // Remains true while the Worker body executes, even if Future.cancel(true)
        // reports done before a blocked socket has actually stopped.
        volatile boolean executing;
        volatile Thread workerThread;
        volatile Future<?> future;
        volatile NativeTaskWorkerRoundService.RoundReport result;
        volatile String error;
        Entry(long owner,UUID task,int piecesPerRound,int maxRounds,int maxSeconds) {
            this.owner=owner;
            this.task=task;
            this.piecesPerRound=piecesPerRound;
            this.maxRounds=maxRounds;
            this.maxSeconds=maxSeconds;
        }
        boolean continuous() {return maxRounds>0;}
    }

    public NativeWorkerJobManager(RoundInvoker invoker,TaskAccess tasks,
                                  NativeTorrentProperties flags,
                                  int threads,int queueSize,int perUserLimit) {
        this(invoker,tasks,flags,threads,queueSize,perUserLimit,thread->{});
    }

    public NativeWorkerJobManager(RoundInvoker invoker,TaskAccess tasks,
                                  NativeTorrentProperties flags,
                                  int threads,int queueSize,int perUserLimit,
                                  ActiveIoCanceller ioCanceller) {
        if(ioCanceller==null)throw new IllegalArgumentException("Missing network canceller");
        this.ioCanceller=ioCanceller;
        if(invoker==null || tasks==null || flags==null ||
                threads<1 || threads>8 || queueSize<1 || queueSize>64 ||
                perUserLimit<1 || perUserLimit>8)
            throw new IllegalArgumentException("Invalid native worker queue configuration");
        this.invoker=invoker;
        this.tasks=tasks;
        this.flags=flags;
        this.perUserLimit=perUserLimit;
        this.executor=new ThreadPoolExecutor(threads,threads,0L,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),runnable->{
            Thread thread=new Thread(runnable,"clipfetch-native-piece-round");
            thread.setDaemon(true);
            return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Enqueue only a READY version owned by this authenticated user. */
    public UUID submit(long owner,UUID task,long revision,int maxPieces) {
        if(owner<=0 || task==null || revision<0 || maxPieces<1 || maxPieces>4)
            throw new IllegalArgumentException("Invalid native work submission");
        awaitTerminalTaskExecuting(owner,task);
        return synchronizedEnqueue(owner,task,revision,maxPieces,0,0);
    }

    /** Bounded multi-round work; no implicit retries beyond the explicit budgets. */
    public UUID submitContinuous(long owner,UUID task,long revision,
                                int piecesPerRound,int maxRounds,int maxSeconds) {
        int maxP = flags != null ? flags.getContinuousMaxPieces() : 4;
        int maxR = flags != null ? flags.getContinuousMaxRounds() : 64;
        int maxS = flags != null ? flags.getContinuousMaxSeconds() : 600;
        if(owner<=0 || task==null || revision<0 || piecesPerRound<1 || piecesPerRound>maxP
                || maxRounds<1 || maxRounds>maxR || maxSeconds<10 || maxSeconds>maxS)
            throw new IllegalArgumentException("Invalid continuous Piece budget");
        if(!flags.isContinuousWorkerEnabled())
            throw new IllegalStateException("Continuous native worker is disabled");
        awaitTerminalTaskExecuting(owner,task);
        return synchronizedEnqueue(owner,task,revision,piecesPerRound,maxRounds,maxSeconds);
    }

    private void awaitTerminalTaskExecuting(long owner,UUID task) {
        Future<?> oldFuture=null;
        Thread oldThread=null;
        synchronized(this) {
            for(Entry e:jobs.values()) {
                if(e.owner==owner && e.task.equals(task) && !active(e) && e.executing) {
                    if(e.workerThread==null || !e.workerThread.isAlive()) {
                        e.executing=false;
                    }else{
                        oldFuture=e.future;
                        oldThread=e.workerThread;
                    }
                }
            }
        }
        if(oldFuture!=null) {
            try {oldFuture.get(1500,TimeUnit.MILLISECONDS);}
            catch(TimeoutException | ExecutionException | CancellationException ignored) {}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        if(oldThread!=null && oldThread.isAlive()) {
            try {oldThread.join(1500);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        synchronized(this) {
            for(Entry e:jobs.values()) {
                if(e.owner==owner && e.task.equals(task) && !active(e)) {
                    if(e.workerThread==null || !e.workerThread.isAlive()) {
                        e.executing=false;
                    }
                }
            }
        }
    }

    private synchronized UUID synchronizedEnqueue(long owner,UUID task,long revision,
                                                  int maxPieces,int maxRounds,int maxSeconds) {
        return enqueue(owner,task,revision,maxPieces,maxRounds,maxSeconds);
    }

    private UUID enqueue(long owner,UUID task,long revision,int maxPieces,
                         int maxRounds,int maxSeconds) {
        requireWorkerEnabled();
        if(flags.isInstanceHeartbeatEnabled() && !instanceAdmissionHealthy.getAsBoolean())
            throw new IllegalStateException("Native worker instance lease is not healthy");
        if(closed)throw new IllegalStateException("Native work queue closed");
        var row=tasks.get(owner,task);
        if(!"READY".equals(row.state()) || row.revision()!=revision)
            throw new IllegalStateException("Only a current READY task may be submitted");
        for(Entry j:jobs.values()) {
            if(!active(j) && j.executing && (j.workerThread==null || !j.workerThread.isAlive())) {
                j.executing=false;
            }
        }
        long pending=jobs.values().stream()
                .filter(j->j.owner==owner && occupiesSlot(j)).count();
        if(pending>=perUserLimit) {
            rejectedSubmissions++;
            throw new RejectedExecutionException("User already has too many native jobs");
        }
        if(jobs.values().stream().anyMatch(j->j.task.equals(task) && occupiesSlot(j))) {
            rejectedSubmissions++;
            throw new RejectedExecutionException("Task already has an active native job");
        }
        pruneHistory();
        if(jobs.size()>=MAX_HISTORY) {
            rejectedSubmissions++;
            throw new RejectedExecutionException("Native job history is full");
        }
        Entry entry=new Entry(owner,task,maxPieces,maxRounds,maxSeconds);
        entry.initialVerified=row.verifiedBytes();
        entry.verified=row.verifiedBytes();
        entry.wire=row.wireBytes();
        entry.selected=row.selectedBytes();
        jobs.put(entry.jobId,entry);
        try {
            entry.future=executor.submit(()->run(entry,revision,maxPieces));
        }catch(RejectedExecutionException e) {
            jobs.remove(entry.jobId);
            rejectedSubmissions++;
            throw e;
        }
        return entry.jobId;
    }

    private void run(Entry entry,long revision,int maxPieces) {
        synchronized(this) {
            if(entry.state!=State.QUEUED)return;
            if(flags.isInstanceHeartbeatEnabled() && !instanceAdmissionHealthy.getAsBoolean()) {
                entry.state=State.CANCELLED;
                entry.error="Native instance lease lost before Worker start";
                try {
                    var current=tasks.get(entry.owner,entry.task);
                    if("READY".equals(current.state()) || "RUNNING".equals(current.state()))
                        tasks.pause(entry.owner,entry.task,current.revision());
                }catch(RuntimeException ignored) {}
                return;
            }
            entry.state=State.RUNNING;
            entry.executing=true;
            entry.workerThread=Thread.currentThread();
        }
        NativeNetworkBudget.Scope networkBudget=null;
        try {
            networkBudget=NativeNetworkBudget.open(entry.continuous()
                    ? Duration.ofSeconds(entry.maxSeconds)
                    : Duration.ofMillis(flags.getNetworkRoundBudgetMillis()));
            if(entry.continuous()) {
                var session=continuousSession;
                if(session!=null)session.begin(entry.owner,entry.task);
                runContinuous(entry,revision);
            }else{
                var result=invoker.run(entry.owner,entry.task,revision,maxPieces);
                synchronized(this) {
                    if(entry.state==State.RUNNING) {
                        entry.result=result;
                        sample(entry,result);
                        entry.state=State.SUCCEEDED;
                    }
                }
            }
        }catch(Throwable failure) {
            if(failure instanceof NativeInstanceLeaseUnavailableException) {
                // An expired/lost instance must not run another fenced round.
                // This is a controlled safety cancellation, not a Piece I/O
                // failure eligible for repeated network retry.
                stopForLostInstanceLease(entry);
            }else if(entry.continuous() && NativeNetworkBudget.exhausted()) {
                stopPartial(entry,"Continuous native network deadline exhausted");
            }else {
                boolean marked=false;
                synchronized(this) {
                    if(entry.state==State.RUNNING) {
                        // Fence the persistent task as well as the transient job. A failed
                        // network round must not leave an unowned READY/RUNNING download.
                        try {
                            var current=tasks.get(entry.owner,entry.task);
                            if("READY".equals(current.state()) || "RUNNING".equals(current.state()))
                                tasks.pause(entry.owner,entry.task,current.revision());
                        }catch(RuntimeException ignored) {
                            // An external CAS transition may already own the task.
                        }
                        entry.error=Thread.currentThread().isInterrupted()
                                ? "Native job interrupted" : describeFailure(failure);
                        entry.state=State.FAILED;
                        marked=true;
                    }
                }
                if(marked)
                    log.warn("原生 Piece 作业失败: task={} owner={}", entry.task, entry.owner, failure);
            }
        }finally {
            if(entry.continuous() && continuousSession!=null) {
                try {continuousSession.end();}
                catch(RuntimeException error){log.warn("Could not release Native continuous session",error);}
            }
            if(networkBudget!=null) {
                try { networkBudget.close(); } catch (Exception ignored) {}
            }
            synchronized(this) {
                entry.executing=false;
                entry.workerThread=null;
            }
        }
    }

    /**
     * Several independently CAS-fenced rounds under one queue reservation.
     * Three rounds with no new verified bytes stop early; time and round budgets
     * prevent background retries from running forever.
     */
    private void runContinuous(Entry entry,long initialRevision) throws IOException {
        long revision=initialRevision;
        long deadline=entry.startedNanos+TimeUnit.SECONDS.toNanos(entry.maxSeconds);
        int stalled=0;
        for(int n=0;n<entry.maxRounds;n++) {
            if(Thread.currentThread().isInterrupted())
                throw new IOException("Continuous native work interrupted");
            synchronized(this) {
                if(entry.state!=State.RUNNING)return;
            }
            if(System.nanoTime()>=deadline) {
                stopPartial(entry,"Native work time budget exhausted");
                return;
            }
            var before=tasks.get(entry.owner,entry.task);
            if(!"READY".equals(before.state()) || before.revision()!=revision)
                throw new IOException("Native task was paused, deleted or changed externally");
            long previous=before.verifiedBytes();
            NativeTaskWorkerRoundService.RoundReport round;
            int failures=0;
            while(true) {
                try {
                    round=invoker.run(entry.owner,entry.task,revision,entry.piecesPerRound);
                    break;
                }catch(IOException io) {
                    var current=tasks.get(entry.owner,entry.task);
                    if(NativeNetworkBudget.deadlineExceeded(io)) {
                        // The round network budget expired; the fenced runner already
                        // paused the task. Keep committed Pieces as a partial round
                        // instead of failing the whole bounded session.
                        round=new NativeTaskWorkerRoundService.RoundReport(0,true,null,current);
                        break;
                    }
                    // Retry only if the durable READY revision is unchanged. Never
                    // resurrect a PAUSED/RUNNING/deleted task or exceed its budget.
                    if(Thread.currentThread().isInterrupted() ||
                            !"READY".equals(current.state()) || current.revision()!=revision)
                        throw io;
                    // A socket stall has already consumed the round's Peer work;
                    // let the next bounded round refresh candidates instead of
                    // repeating the same network path on the same revision.
                    if(!shouldRetryContinuousFailure(io)) {
                        round=new NativeTaskWorkerRoundService.RoundReport(0,true,null,current);
                        break;
                    }
                    if(++failures>=3)throw io;
                    long remaining=deadline-System.nanoTime();
                    if(remaining<=0)throw io;
                    long delay=boundedContinuousBackoffMillis(failures,stalled,remaining);
                    try {if(delay>0)Thread.sleep(delay);}
                    catch(InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Continuous Piece retry interrupted",cancelled);
                    }
                }
            }
            synchronized(this) {
                if(entry.state!=State.RUNNING)return;
                entry.result=round;
                sample(entry,round);
            }
            var after=tasks.get(entry.owner,entry.task);
            if("COMPLETED".equals(after.state())) {
                synchronized(this) {
                    if(entry.state==State.RUNNING)entry.state=State.SUCCEEDED;
                }
                return;
            }
            if(!"READY".equals(after.state()) && !"PAUSED".equals(after.state()))
                throw new IOException("Native task is no longer available for continued work");
            if(after.verifiedBytes()<=previous)stalled++; else stalled=0;
            int maxStalled = flags != null ? flags.getContinuousStalledRounds() : 3;
            if(stalled>=maxStalled) {
                stopPartial(entry,"No verified download progress in three rounds");
                return;
            }
            if(n+1>=entry.maxRounds || System.nanoTime()>=deadline) {
                stopPartial(entry,"Bounded continuous work budget exhausted");
                return;
            }
            // Serialize automatic READY preparation with cancellation. Cancellation
            // always wins if it has already marked this job as CANCELLED.
            synchronized(this) {
                if(entry.state!=State.RUNNING)return;
                if("PAUSED".equals(after.state())) {
                    var ready=tasks.prepare(entry.owner,entry.task,after.revision());
                    if(!"READY".equals(ready.state()))
                        throw new IOException("Could not prepare the next Piece round");
                    revision=ready.revision();
                }else{
                    revision=after.revision();
                }
            }
            if(stalled>0) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) {
                    stopPartial(entry,"Native work time budget exhausted");
                    return;
                }
                long backoff=boundedContinuousBackoffMillis(0,stalled,remaining);
                try {
                    if(backoff>0)Thread.sleep(backoff);
                }catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Continuous native work cancelled",e);
                }
            }
        }
    }

    /** Bounded owner-facing failure text; never prints stack details to the API. */
    private static String describeFailure(Throwable failure) {
        String message=failure.getMessage();
        String text=failure.getClass().getSimpleName()
                + (message==null || message.isBlank() ? "" : ": "+message);
        return text.length()<=160 ? text : text.substring(0,160);
    }

    private void stopForLostInstanceLease(Entry entry) {
        synchronized(this) {
            if(entry.state!=State.RUNNING)return; // explicit cancellation already won
            try {
                var current=tasks.get(entry.owner,entry.task);
                if("READY".equals(current.state()) || "RUNNING".equals(current.state()))
                    tasks.pause(entry.owner,entry.task,current.revision());
            }catch(RuntimeException failure) {
                // Never re-claim an unknown lease or silently resurrect the task.
                log.warn("Could not confirm PAUSED after native instance lease loss: task={} owner={}",
                        entry.task,entry.owner,failure);
            }
            entry.error="Native instance lease unavailable; Worker safely stopped";
            entry.state=State.CANCELLED;
            log.warn("Native instance lease unavailable; cancelling fenced Piece job: task={} owner={}",
                    entry.task,entry.owner);
        }
    }

    private void stopPartial(Entry entry,String reason) {
        synchronized(this) {
            if(entry.state!=State.RUNNING)return;
            try {
                var state=tasks.get(entry.owner,entry.task);
                if("READY".equals(state.state()))
                    tasks.pause(entry.owner,entry.task,state.revision());
                else if("RUNNING".equals(state.state()))
                    tasks.pause(entry.owner,entry.task,state.revision());
            }catch(RuntimeException failure) {
                entry.error="Native task changed while stopping a limited work session";
                entry.state=State.FAILED;
                return;
            }
            entry.error=reason;
            entry.state=State.PARTIAL;
        }
    }

    private static void sample(Entry entry,NativeTaskWorkerRoundService.RoundReport report) {
        entry.rounds++;
        entry.candidatePeers=report.candidatePeers();
        if(report.download()!=null)
            entry.committed+=report.download().committedPieces();
        if(report.task()!=null)sampleProgress(entry,report.task());
        else entry.recentSpeed=0.0;
    }

    private static void sampleProgress(Entry entry,NativeTaskLifecycleService.TaskState task) {
        long now=System.nanoTime();
        long interval=Math.max(1,now-(entry.lastSampleNanos==0
                ?entry.startedNanos:entry.lastSampleNanos));
        entry.lastSampleNanos=now;
        long bytes=task.verifiedBytes();
        long delta=Math.max(0,bytes-entry.verified);
        entry.recentSpeed=delta*1_000_000_000.0/interval;
        entry.verified=bytes;
        entry.selected=task.selectedBytes();
        entry.wire=task.wireBytes();
    }

    /**
     * Recover an in-memory job identifier after a browser reload.
     * Ownership is verified against the persisted task before inspecting
     * the volatile queue; a JVM restart legitimately returns an empty result.
     */
    public synchronized Optional<Snapshot> latestOwnedTaskJob(long owner,UUID task) {
        if(owner<=0 || task==null)throw new NoSuchElementException("Native task unavailable");
        tasks.get(owner,task);
        Entry newest=null;
        for(Entry e:jobs.values()) {
            if(e.owner==owner && e.task.equals(task)) {
                if(occupiesSlot(e))return Optional.of(snapshot(e));
                newest=e;
            }
        }
        return newest==null?Optional.empty():Optional.of(snapshot(newest));
    }

    public synchronized Snapshot status(long owner,UUID jobId) {
        return snapshot(owned(owner,jobId));
    }
    public synchronized Metrics metrics(long owner,UUID jobId) {
        Entry e=owned(owner,jobId);
        if(e.state==State.RUNNING)sampleProgress(e,tasks.get(owner,e.task));
        long elapsedNanos=Math.max(1,System.nanoTime()-e.startedNanos);
        long elapsed=elapsedNanos/1_000_000L;
        double average=1_000_000_000.0*Math.max(0,e.verified-e.initialVerified)/elapsedNanos;
        long now=System.nanoTime();
        if(e.state==State.RUNNING && e.continuous()) {
            long read=Math.max(e.receivedPayloadBytes,
                    Math.max(0L,networkTelemetry.receivedPayloadBytes(owner,e.task)));
            // A UI poll is not a fixed-rate network timer. Keep an EWMA
            // across short polls, but age it out after genuine inactivity.
            e.networkSpeed=e.networkRateMeter.sample(read,now);
            e.receivedPayloadBytes=read;
            e.lastNetworkSampleNanos=now;
        }else e.networkSpeed=0;
        var peers=peerTelemetry.status(owner,e.task);
        var staged=e.state==State.RUNNING?
                stagedTelemetry.progress(owner,e.task):
                new NativeTaskWorkerRoundService.StagedProgress(0,0);
        var sockets=e.state==State.RUNNING?
                connectionTelemetry.stats(owner,e.task):
                new PeerPieceDownloadSession.TaskConnectionStats(0,0,0,0,0,0,0,0);
        var queue=e.state==State.RUNNING?
                requestQueueTelemetry.stats(owner,e.task):
                new NativePeerRequestTelemetry().snapshot();
        var visibleQueue=new RequestQueueMetrics(queue.sentBlocks(),queue.ackedBlocks(),
                queue.outstandingBlocks(),queue.capacityBlocks(),queue.occupancyPercent(),
                queue.rttP50Millis(),queue.rttP95Millis());
        return new Metrics(e.jobId,e.task,e.state,e.rounds,e.committed,e.verified,
                e.selected,e.wire,e.recentSpeed,average,elapsed,e.createdAt,
                e.receivedPayloadBytes,e.networkSpeed,peers.poolPeers(),
                peers.provenPeers(),peers.degradedPeers(),peers.refreshes(),
                staged.pieces(),staged.selectedBytes(),sockets.openedSockets(),
                sockets.reuseHits(),sockets.crossRoundReuseHits(),sockets.idleSockets(),
                sockets.targetPeerActors(),sockets.activePeerActors(),
                sockets.actorPeerReassignments(),sockets.retiredPeerSockets(),
                visibleQueue);
    }

    /**
     * Fence persistent task state first. Only when PAUSED/terminal do we
     * interrupt the Future. A cancelled queued job cannot start after this.
     */
    /** Local containment after lease loss. Not a distributed fencing guarantee. */
    public int cancelAllForLostInstance() {
        List<Entry> activeEntries;
        synchronized(this) {
            activeEntries=jobs.values().stream().filter(NativeWorkerJobManager::active).toList();
        }
        int cancelled=0;
        for(var entry:activeEntries) {
            try {
                cancel(entry.owner,entry.jobId);
                cancelled++;
            }catch(RuntimeException ignored) {
                // Failure to fence a persistent task must not be reported as success.
            }
        }
        return cancelled;
    }

    public Snapshot cancel(long owner,UUID jobId) {
        Entry entry;
        Future<?> future;
        synchronized(this) {
            entry=owned(owner,jobId);
            if(!active(entry)) {
                if(entry.workerThread==null || !entry.workerThread.isAlive()) {
                    entry.executing=false;
                }
                return snapshot(entry);
            }
            for(int attempt=0; attempt<3; attempt++) {
                try {
                    var row=tasks.get(owner,entry.task);
                    if("READY".equals(row.state()) || "RUNNING".equals(row.state())) {
                        tasks.pause(owner,entry.task,row.revision());
                    }
                    break;
                }catch(IllegalStateException ex) {
                    // Retry with latest revision if concurrently committed by Worker
                }
            }
            entry.state=State.CANCELLED;
            if(entry.workerThread!=null)ioCanceller.abort(entry.workerThread);
            if(entry.future!=null)entry.future.cancel(true);
            future=entry.future;
            if(entry.workerThread==null || !entry.workerThread.isAlive()) {
                entry.executing=false;
            }
            executor.purge();
        }
        if(future!=null) {
            try {future.get(300,TimeUnit.MILLISECONDS);}
            catch(TimeoutException | ExecutionException | CancellationException ignored) {}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        Thread workerThread=entry.workerThread;
        if(workerThread!=null && workerThread.isAlive()) {
            try {workerThread.join(500);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        synchronized(this) {
            if(entry.workerThread==null || !entry.workerThread.isAlive()) {
                entry.executing=false;
            }
            return snapshot(entry);
        }
    }

    public Snapshot await(long owner,UUID jobId,Duration timeout) {
        if(timeout==null || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Invalid wait timeout");
        Future<?> future;
        synchronized(this) {
            Entry e=owned(owner,jobId);
            if(!active(e))return snapshot(e);
            future=e.future;
        }
        if(future!=null) {
            try {future.get(timeout.toNanos(),TimeUnit.NANOSECONDS);}
            catch(TimeoutException | ExecutionException | CancellationException ignored) {}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        return status(owner,jobId);
    }

    /**
     * Explicit operator-only recovery for a SINGLE confirmed application instance,
     * after a crash and prior to accepting new jobs. Never perform it automatically
     * across competing JVMs: another instance could legitimately own RUNNING.
     */
    public synchronized NativeTaskLifecycleService.TaskState recoverOwnedOrphan(
            long owner,UUID task,long revision) {
        if(!flags.isSingleInstanceOrphanRecoveryEnabled())
            throw new IllegalStateException("Single-instance orphan recovery disabled");
        if(closed || executor.getActiveCount()!=0 ||
                jobs.values().stream().anyMatch(NativeWorkerJobManager::occupiesSlot))
            throw new IllegalStateException("Cannot recover while local native jobs are active");
        var row=tasks.get(owner,task);
        if(!"RUNNING".equals(row.state()) || row.revision()!=revision)
            throw new IllegalStateException("Task has no matching orphan RUNNING revision");
        return tasks.fenceOrphan(owner,task,revision);
    }

    private void requireWorkerEnabled() {
        if(!flags.isEnabled() || !flags.isWorkerRoundEnabled() ||
                !flags.isAsyncWorkerEnabled())
            throw new IllegalStateException("Native async worker is disabled");
    }
    private static boolean active(Entry e) {
        return e.state==State.QUEUED || e.state==State.RUNNING;
    }
    private static boolean occupiesSlot(Entry e) {
        return active(e) || e.executing;
    }
    private Entry owned(long owner,UUID id) {
        Entry e=jobs.get(id);
        if(owner<=0 || e==null || e.owner!=owner)
            throw new NoSuchElementException("Native work job unavailable");
        return e;
    }
    private static Snapshot snapshot(Entry e) {
        // A bounded worker can finish PARTIAL after committing valid Pieces.
        // Retain verified progress across terminal states, not just SUCCEEDED.
        // CANCELLED jobs expose only samples recorded before cancellation;
        // late worker results are discarded by run()'s state guard.
        int peers=e.candidatePeers>0?e.candidatePeers:(e.result==null?0:e.result.candidatePeers());
        return new Snapshot(e.jobId,e.task,e.state,peers,e.committed,
                e.verified,e.error,e.createdAt);
    }
    private void pruneHistory() {
        if(jobs.size()<MAX_HISTORY-16)return;
        Iterator<Entry> it=jobs.values().iterator();
        while(it.hasNext() && jobs.size()>MAX_HISTORY-32) {
            if(!occupiesSlot(it.next()))it.remove();
        }
    }
    @Override public synchronized void close() {
        if(closed)return;
        closed=true;
        for(Entry e:jobs.values()) {
            if(active(e)) {
                try {
                    var row=tasks.get(e.owner,e.task);
                    if("READY".equals(row.state()) || "RUNNING".equals(row.state()))
                        tasks.pause(e.owner,e.task,row.revision());
                }catch(RuntimeException ignored) {
                    // Shutdown may overlap database closure; persisted CAS still guards progress.
                }
                e.state=State.CANCELLED;
                if(e.workerThread!=null)ioCanceller.abort(e.workerThread);
                if(e.future!=null)e.future.cancel(true);
            }
        }
        executor.shutdownNow();
    }
}
