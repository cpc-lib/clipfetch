package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Limited Piece download coordinator. TCP I/O may be parallel, but Checkpoint
 * and database CAS commits are ALWAYS serialized by the owning Worker thread.
 */
public final class PieceDownloadCoordinator {
    /** User-configured upper bound; the in-flight memory budget may reduce it. */
    public static final int MAX_PARALLEL_PIECES = 256;
    private static final long MAX_IN_FLIGHT_PIECE_BYTES = 512L * 1024 * 1024;
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(InetSocketAddress endpoint,TorrentInfo torrent,byte[] peerId,
                     PieceBlockPlanner planner,int piece,Duration deadline) throws IOException;
    }
    @FunctionalInterface
    public interface VerifiedCommitListener {
        void afterCommit(int piece,int wirePieceBytes,long verifiedSelectedBytes) throws IOException;
    }
    @FunctionalInterface
    public interface WorkerGuard {
        void beforeNetworkRequest() throws IOException;
    }
    public interface ChildThreads {
        void register(Thread parent,Thread child);
        void unregister(Thread parent,Thread child);
        default void beginRound(Thread owner) {}
        default void endRound(Thread owner) {}
        static ChildThreads none() {
            return new ChildThreads() {
                public void register(Thread parent,Thread child) {}
                public void unregister(Thread parent,Thread child) {}
            };
        }
    }
    public record RoundResult(int attemptedPieces,int committedPieces,int peerAttempts,
                              List<Integer> failedPieces,long verifiedSelectedBytes,
                              long selectedTotalBytes,double progressPercent) {
        public RoundResult {failedPieces=List.copyOf(failedPieces);}
    }
    private record Fetched(int piece,byte[] bytes,int attempts) {}

    private final VerifiedPieceCheckpoint checkpoint;
    private final Fetcher fetcher;
    private final PieceBlockPlanner planner;
    private final List<InetSocketAddress> peers;
    private final byte[] peerId;
    private final int attemptsPerPiece;
    private final Duration perPeerBudget;
    private final int parallelPieces;
    private final ChildThreads childThreads;
    private final PeerPieceAvailability availability;
    @FunctionalInterface
    interface PeerRefresher {
        List<InetSocketAddress> discover() throws IOException;
    }
    private PeerRefresher peerRefresher;
    private int peerCapacity=256;
    private boolean multiplexedMailbox;
    private int multiplexedExplorationSlots=32;
    /** Opt-in only: production default keeps the validated Piece-centric path. */
    void enableMultiplexedMailbox() {multiplexedMailbox=true;}
    void configureMultiplexedExplorationSlots(int slots) {
        if(slots<1 || slots>MAX_PARALLEL_PIECES)
            throw new IllegalArgumentException("Invalid multiplexed exploration slots");
        multiplexedExplorationSlots=slots;
    }
    /** Only the owner/task-scoped Worker may enable dynamic discovery. */
    void enablePeerRefresh(PeerRefresher source,int limit) {
        if(source==null || limit<1 || limit>256)
            throw new IllegalArgumentException("Invalid dynamic discovery");
        peerRefresher=source;
        peerCapacity=limit;
    }
    private PeerHealthTracker roundHealth;
    private final java.util.concurrent.atomic.LongAdder peerFetchNanos=
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder checkpointCommitNanos=
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder progressCasNanos=
            new java.util.concurrent.atomic.LongAdder();
    private volatile int activeWorkerSlots=1;
    private int commitBatchSize=1;
    /** Opt-in only for the fenced production Worker. Existing callers keep per-Piece CAS. */
    public synchronized void configureGroupCommit(int pieces) {
        if(pieces<1 || pieces>256)throw new IllegalArgumentException("Invalid Piece commit group");
        commitBatchSize=pieces;
    }

    /** Timings are summed over all concurrent Peers (not wall-clock transfer time). */
    public record Performance(long peerFetchMillis,long commitMillis,long databaseMillis,
                              int workerSlots) {}
    public Performance performance() {
        return new Performance(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(peerFetchNanos.sum()),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(checkpointCommitNanos.sum()),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(progressCasNanos.sum()),activeWorkerSlots);
    }

    public PieceDownloadCoordinator(VerifiedPieceCheckpoint checkpoint,Fetcher fetcher,
                                    List<InetSocketAddress> peers,byte[] peerId,
                                    int attemptsPerPiece,Duration perPeerBudget) {
        this(checkpoint,fetcher,peers,peerId,attemptsPerPiece,perPeerBudget,
                1,ChildThreads.none(),null);
    }

    public PieceDownloadCoordinator(VerifiedPieceCheckpoint checkpoint,Fetcher fetcher,
                                    List<InetSocketAddress> peers,byte[] peerId,
                                    int attemptsPerPiece,Duration perPeerBudget,
                                    int parallelPieces,ChildThreads childThreads) {
        this(checkpoint,fetcher,peers,peerId,attemptsPerPiece,perPeerBudget,
                parallelPieces,childThreads,null);
    }

    public PieceDownloadCoordinator(VerifiedPieceCheckpoint checkpoint,Fetcher fetcher,
                                    List<InetSocketAddress> peers,byte[] peerId,
                                    int attemptsPerPiece,Duration perPeerBudget,
                                    int parallelPieces,ChildThreads childThreads,
                                    PeerPieceAvailability availability) {
        if(checkpoint==null || fetcher==null || peers==null || peers.isEmpty() ||
                peers.size()>256 || peerId==null || peerId.length!=20 ||
                attemptsPerPiece<1 || attemptsPerPiece>32 ||
                perPeerBudget==null || perPeerBudget.isNegative() || perPeerBudget.isZero() ||
                perPeerBudget.compareTo(Duration.ofSeconds(60))>0 ||
                parallelPieces<1 || parallelPieces>MAX_PARALLEL_PIECES || childThreads==null)
            throw new IllegalArgumentException("Invalid Piece coordinator");
        for(var peer:peers)
            if(peer==null || peer.getPort()<1 || peer.getPort()>65535)
                throw new IllegalArgumentException("Invalid candidate Peer");
        this.checkpoint=checkpoint;
        this.fetcher=fetcher;
        this.planner=new PieceBlockPlanner(checkpoint.torrentInfo(),checkpoint.selection());
        this.peers=List.copyOf(new LinkedHashSet<>(peers));
        this.peerId=peerId.clone();
        this.attemptsPerPiece=attemptsPerPiece;
        this.perPeerBudget=perPeerBudget;
        this.parallelPieces=parallelPieces;
        this.childThreads=childThreads;
        if(availability!=null && availability.pieceCount()!=this.planner.pieceCount())
            throw new IllegalArgumentException("Invalid Peer availability");
        this.availability=availability!=null?availability:
                new PeerPieceAvailability(this.planner.pieceCount(),Map.of());
    }

    /** Scoped by owner/task in the orchestrator, never shared across users. */
    void useRoundHealth(PeerHealthTracker health) {
        if(health==null)throw new IllegalArgumentException("Missing Peer health");
        this.roundHealth=health;
    }

    public boolean usesCheckpoint(VerifiedPieceCheckpoint candidate) {
        return checkpoint==candidate;
    }
    public static PieceDownloadCoordinator network(VerifiedPieceCheckpoint checkpoint,
                                                     List<InetSocketAddress> peers,byte[] peerId) {
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(false));
        return new PieceDownloadCoordinator(checkpoint,session::downloadPiece,peers,
                peerId,3,Duration.ofSeconds(12));
    }

    public synchronized RoundResult runRound(int maxPieces) throws IOException {
        return runRound(maxPieces,(piece,wire,verified)->{});
    }
    public synchronized RoundResult runRound(int maxPieces,VerifiedCommitListener listener)
            throws IOException {
        return runRound(maxPieces,listener,()->{});
    }
    public synchronized RoundResult runRound(int maxPieces,VerifiedCommitListener listener,
                                             WorkerGuard guard) throws IOException {
        if(listener==null || guard==null)throw new IllegalArgumentException("Missing round callbacks");
        if(maxPieces<1 || maxPieces>256)
            throw new IllegalArgumentException("Invalid per-round Piece budget");
        Thread owner=Thread.currentThread();
        peerFetchNanos.reset();
        checkpointCommitNanos.reset();
        progressCasNanos.reset();
        activeWorkerSlots=1;
        childThreads.beginRound(owner);
        try {
            if(parallelPieces==1)return sequential(maxPieces,listener,guard);
            if(multiplexedMailbox && childThreads instanceof PeerPieceDownloadSession session &&
                    session.actorScope(owner) && peers.size()>1)
                return parallelMultiplexed(maxPieces,listener,guard,session);
            return parallel(maxPieces,listener,guard);
        }finally {
            childThreads.endRound(owner); // close cached sockets after network children stop
        }
    }

    private RoundResult parallelMultiplexed(int maxPieces,VerifiedCommitListener listener,
                                           WorkerGuard guard,PeerPieceDownloadSession session)
            throws IOException {
        // Bound all provisional SHA1 buffers and completed results. An
        // experimental round is intentionally smaller than normal 128 Piece
        // batches until real-swarm benchmarking validates resource behavior.
        int pieceBytes=planner.pieceBytes(0);
        int memoryPieceCap=(int)Math.max(1L,MAX_IN_FLIGHT_PIECE_BYTES/pieceBytes);
        int limit=Math.min(maxPieces,memoryPieceCap);
        var scheduled=availability.choose(planner.wantedPieces(),
                checkpoint.verifiedPieces(),limit);
        if(scheduled.isEmpty())return result(0,0,0,List.of());
        // Health-aware Actor supervisor: scale target actors by verified Peer
        // count, capped at min(parallelPieces, peers, TaskPeerActorPool.HARD_MAX).
        // The shared task health tracker carries proven-Peer scores across rounds.
        // Exploration slots are configured separately from the total Worker cap.
        var roundTracker=roundHealth!=null?roundHealth:new PeerHealthTracker(peers);
        int actorCap=Math.min(Math.min(parallelPieces,peers.size()),TaskPeerActorPool.HARD_MAX);
        int actors=PeerMultiplexedRound.computeTargetActors(roundTracker,
                Math.min(multiplexedExplorationSlots,actorCap),actorCap);
        activeWorkerSlots=actors;
        var started=System.nanoTime();
        var healthy=new ArrayList<InetSocketAddress>(peers);
        // Degraded peers are scored in the legacy scheduler. The
        // experimental path retains the exact supplied bounded peer order.
        // Only the original task owner can stage or publish verified Pieces.
        // Network Peer actors stream completed SHA-1 Pieces into a bounded
        // result queue while continuing cross-Piece Block requests.
        class Progress {
            int committed,stagedWire,lastPiece=-1;
            long stagedSince;
            void flush(boolean finishing) throws IOException {
                if(!NativeGroupCommitPolicy.shouldPublish(checkpoint.stagedGroupSize(),
                        commitBatchSize,stagedWire,stagedSince,
                        System.nanoTime(),finishing))return;
                int count=checkpoint.stagedGroupSize();
                flushGroup(lastPiece,stagedWire,listener,guard);
                committed+=count;
                stagedWire=0;lastPiece=-1;stagedSince=0;
            }
            void accept(int piece,byte[] verified) throws IOException {
                checkInterrupted();
                guard.beforeNetworkRequest();
                long at=System.nanoTime();
                try {checkpoint.stageGroupPiece(piece,verified,guard::beforeNetworkRequest);}
                finally {checkpointCommitNanos.add(System.nanoTime()-at);}
                if(stagedSince==0)stagedSince=System.nanoTime();
                stagedWire+=verified.length;
                lastPiece=piece;
                flush(false);
            }
        }
        var progress=new Progress();
        // Bounded, interruptible async Peer refresh for the multiplexed path.
        // The legacy parallel() path already refreshes mid-round; the mailbox
        // path must too, or a public swarm whose tracker peers are mostly dead
        // never replenishes (frontend showed "refreshes 0"). A merged Peer is
        // visible to the shared round tracker immediately: the next
        // leaseExclusive on any Actor thread can claim it within the same round.
        ExecutorService discovery=peerRefresher==null?null:
                Executors.newSingleThreadExecutor(r->{
                    Thread t=new Thread(r,"clipfetch-native-peer-refresh");
                    t.setDaemon(true);
                    return t;
                });
        @SuppressWarnings("unchecked")
        final Future<List<InetSocketAddress>>[] refreshRef=new Future[1];
        if(discovery!=null && roundTracker.needsRefresh(scheduled,availability)) {
            guard.beforeNetworkRequest();
            refreshRef[0]=discovery.submit(peerRefresher::discover);
        }
        try {
            long requestedSeconds=Math.max(30L,Math.min(180L,
                    Math.max((long)perPeerBudget.toSeconds()*4L,(long)scheduled.size()*2L)));
            Duration budget=NativeNetworkBudget.cap(Duration.ofSeconds(requestedSeconds));
            var response=PeerMultiplexedRound.fetch(Thread.currentThread(),session,
                    checkpoint.torrentInfo(),planner,scheduled,healthy,peerId,
                    availability,actors,budget,guard,
                    new PeerMultiplexedRound.VerifiedPieceConsumer() {
                        @Override public void accepted(int piece,byte[] bytes)
                                throws IOException {progress.accept(piece,bytes);}
                        @Override public boolean isBusy() {
                            var pending=refreshRef[0];
                            return pending!=null && !pending.isDone();
                        }
                        @Override public boolean idle() throws IOException {
                            progress.flush(false);
                            var pending=refreshRef[0];
                            if(pending==null || !pending.isDone())return false;
                            int added=0;
                            try {
                                var discovered=pending.get();
                                if(!checkpoint.isComplete())guard.beforeNetworkRequest();
                                added=roundTracker.mergeDiscovered(discovered,peerCapacity);
                                if(added>0)
                                    org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                                            "native_multiplexed_refresh added={} pool={}",
                                            added,roundTracker.peerCount());
                            }catch(ExecutionException ignored) {
                                // A timed-out optional discovery never invalidates good Peers.
                            }catch(InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Dynamic Peer discovery interrupted",interrupted);
                            }
                            refreshRef[0]=null;
                            // Keep replenishing while the pool is still starved; the
                            // refresher itself throttles Tracker/DHT to one call per 15s.
                            if(discovery!=null && !checkpoint.isComplete() && roundTracker.needsRefresh(scheduled,availability)) {
                                guard.beforeNetworkRequest();
                                refreshRef[0]=discovery.submit(peerRefresher::discover);
                            }
                            return added>0;
                        }
                    },roundTracker);
            peerFetchNanos.add(System.nanoTime()-started);
            // Collect a refresh that completed during the slow tail so this
            // round's snapshot carries it into the cross-round candidate cache.
            var tailPending=refreshRef[0];
            if(tailPending!=null && tailPending.isDone()) {
                try {
                    var discovered=tailPending.get();
                    if(!checkpoint.isComplete())guard.beforeNetworkRequest();
                    roundTracker.mergeDiscovered(discovered,peerCapacity);
                }catch(ExecutionException ignored) {
                }catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Dynamic Peer discovery interrupted",interrupted);
                }
                refreshRef[0]=null;
            }
            progress.flush(true);
            var failed=new ArrayList<Integer>();
            for(int piece:scheduled)
                if(!response.verified().containsKey(piece))failed.add(piece);
            org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                    "native_multiplexed actors={} pieces={} committed={} failed={} receivedBlocks={} staleBlocksDropped={} elapsedMs={}",
                    actors,scheduled.size(),progress.committed,failed.size(),
                    response.receivedBlocks(),response.staleBlocksDropped(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            return result(scheduled.size(),progress.committed,response.openedPeers(),failed);
        }finally {
            if(refreshRef[0]!=null)refreshRef[0].cancel(true);
            if(discovery!=null)discovery.shutdownNow();
            checkpoint.discardStagedGroup();
        }
    }

    private RoundResult sequential(int maxPieces,VerifiedCommitListener listener,WorkerGuard guard)
            throws IOException {
        BitSet wanted=planner.wantedPieces(),completed=checkpoint.verifiedPieces();
        List<Integer> failed=new ArrayList<>();
        int attempted=0,committed=0,peerAttempts=0;
        // Retain failures across Pieces in this round; a bad TCP Peer must not be first again.
        var health=roundHealth!=null?roundHealth:new PeerHealthTracker(peers);
        for(int piece:availability.choose(wanted,completed,maxPieces)) {
            checkInterrupted();
            NativeNetworkBudget.check();
            attempted++;
            boolean success=false;
            Set<InetSocketAddress> tried=new HashSet<>();
            for(int attempt=0;attempt<attemptsPerPiece;attempt++) {
                checkInterrupted();
                NativeNetworkBudget.check();
                guard.beforeNetworkRequest();
                var peer=health.select(piece,tried,availability);
                if(peer==null)break;
                tried.add(peer);
                peerAttempts++;
                byte[] bytes;
                long fetchStarted=System.nanoTime();
                try {
                    bytes=fetcher.fetch(peer,checkpoint.torrentInfo(),peerId.clone(),
                            planner,piece,NativeNetworkBudget.cap(perPeerBudget));
                    NativeNetworkBudget.check();
                    if(bytes==null || !PieceHashVerifier.verify(checkpoint.torrentInfo(),piece,bytes))
                        throw new IOException("Remote Peer supplied invalid Piece");
                }catch(IOException | RuntimeException peerError) {
                    if(!(peerError instanceof PeerRoundConnectionPool.PieceUnavailableException))
                        health.failure(peer);
                    NativeNetworkBudget.check();
                    if(Thread.currentThread().isInterrupted())
                        throw new IOException("Native Piece worker cancelled",peerError);
                    continue;
                }finally {
                    peerFetchNanos.add(System.nanoTime()-fetchStarted);
                }
                health.success(peer,bytes.length,System.nanoTime()-fetchStarted);
                NativeNetworkBudget.check();
                guard.beforeNetworkRequest();
                long persistedAt=System.nanoTime();
                try { checkpoint.commit(piece,bytes,guard::beforeNetworkRequest); }
                finally {checkpointCommitNanos.add(System.nanoTime()-persistedAt);}
                long casAt=System.nanoTime();
                try { listener.afterCommit(piece,bytes.length,checkpoint.verifiedSelectedBytes()); }
                finally {progressCasNanos.add(System.nanoTime()-casAt);}
                committed++;
                success=true;
                break;
            }
            if(!success)failed.add(piece);
        }
        return result(attempted,committed,peerAttempts,failed);
    }

    private RoundResult parallel(int maxPieces,VerifiedCommitListener listener,WorkerGuard guard)
            throws IOException {
        BitSet wanted=planner.wantedPieces(),done=checkpoint.verifiedPieces();
        List<Integer> scheduled=availability.choose(wanted,done,maxPieces);
        if(scheduled.isEmpty())return result(0,0,0,List.of());
        final Thread owner=Thread.currentThread();
        var health=roundHealth!=null?roundHealth:new PeerHealthTracker(peers);
        // Bound simultaneously buffered verified Piece bodies even for 8 MiB torrents.
        // A 256 KiB torrent can use all 64 configured lanes under this budget.
        int memorySlots=effectiveParallelWorkers(parallelPieces,scheduled.size(),planner.pieceBytes(0));
        // Streaming group writes release data immediately. Reserve one Piece
        // buffer against the byte budget, but do not unnecessarily reduce
        // concurrency when the configured worker count is already below budget.
        if(commitBatchSize>1) {
            int pieceBytes=planner.pieceBytes(0);
            long available=Math.max(1L,(MAX_IN_FLIGHT_PIECE_BYTES-pieceBytes)/pieceBytes);
            memorySlots=(int)Math.min(memorySlots,available);
        }
        int eligible=health.eligibleCount(scheduled,availability);
        int target=health.targetWindow();
        int workers=health.adaptiveSlots(parallelPieces,memorySlots,scheduled,availability);
        org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                "native_piece_admission peers={} eligible={} target={} active={} configured={} "
                + "pieces={} pieceBytes={} knownBitfields={}",
                peers.size(),eligible,target,workers,parallelPieces,scheduled.size(),
                planner.pieceBytes(0),availability.reports());
        activeWorkerSlots=workers;
        // Reuse the authenticated task's actor threads between Piece rounds.
        // A single-shot round without task ownership keeps its isolated pool.
        var actorPool=childThreads instanceof PeerPieceDownloadSession peerSession
                ?peerSession.borrowActors(owner,workers):null;
        boolean taskActors=actorPool!=null;
        ExecutorService executor=taskActors?actorPool:
                Executors.newFixedThreadPool(workers,r->{
                    var thread=new Thread(null,r,"clipfetch-native-peer-piece",0,false);
                    thread.setDaemon(true);
                    return thread;
                });
        boolean roundFinished=false;
        // Persistent Peer actors choose the Peer first and claim an eligible
        // Piece from a synchronized owner/task-local queue. Other callers
        // retain the well-tested fixed-Piece scheduling path.
        var peerQueue=taskActors?new PeerCentricPieceQueue(scheduled):null;
        if(peerQueue!=null)
            org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                    "native_peer_centric active={} plannedPieces={} peers={} firstPiece={}",
                    workers,scheduled.size(),health.peerCount(),scheduled.get(0));
        var completed=new ExecutorCompletionService<Fetched>(executor);
        // One bounded, interruptible async refresh per round; never re-run a
        // blocked Tracker on the Piece owner thread.
        ExecutorService discovery=peerRefresher==null?null:
                Executors.newSingleThreadExecutor(r->{
                    Thread t=new Thread(r,"clipfetch-native-peer-refresh");
                    t.setDaemon(true);
                    return t;
                });
        Future<List<InetSocketAddress>> pendingRefresh=null;
        boolean refreshStarted=false;
        Set<Future<Fetched>> futures=new HashSet<>();
        int attempts=0,committed=0;
        List<Integer> failed=new ArrayList<>();
        int stagedWireBytes=0,stagedLastPiece=-1;
        long stagedSinceNanos=0;
        try {
            // Submit only one window of work. Replenish after each result is
            // durably committed; otherwise completed Future payloads can accumulate
            // to all maxPieces*pieceBytes even with a small worker thread pool.
            int nextPiece=0;
            while(nextPiece<workers) {
                checkInterrupted();
                NativeNetworkBudget.check();
                int piece=peerQueue==null?scheduled.get(nextPiece):-1;
                futures.add(submitPiece(completed,piece,owner,health,guard,peerQueue));
                nextPiece++;
            }
            // Never let a slow tail Peer hold fsynced-but-unpublished progress
            // indefinitely. Prefer 64-Piece groups; commit a short idle tail.
            for(int i=0;i<scheduled.size();) {
                checkInterrupted();
                NativeNetworkBudget.check();
                if(discovery!=null && !refreshStarted &&
                        (health.needsRefresh(scheduled,availability) ||
                         failed.size()>=Math.max(3,workers/2))) {
                    guard.beforeNetworkRequest();
                    pendingRefresh=discovery.submit(peerRefresher::discover);
                    refreshStarted=true;
                }
                if(pendingRefresh!=null && pendingRefresh.isDone()) {
                    try {
                        var discovered=pendingRefresh.get();
                        guard.beforeNetworkRequest(); // never accept after a fence loss
                        int before=health.peerCount();
                        int added=health.mergeDiscovered(discovered,peerCapacity);
                        if(added>0) {
                            int updated=health.adaptiveSlots(parallelPieces,memorySlots,
                                    scheduled,availability);
                            if(updated>workers) {
                                // A task-scoped actor executor may retain 64 core
                                // threads from the previous round even if this
                                // round started with 8 slots. Never set its max
                                // to 16 while its core is still 64.
                                TaskPeerActorPool.ensureCapacity(
                                        (ThreadPoolExecutor)executor,updated);
                                workers=updated;
                                activeWorkerSlots=workers;
                            }
                        }
                        org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                                "native_peer_refresh initial={} discovered={} added={} active={} remainingPieces={}",
                                before,discovered.size(),added,workers,scheduled.size()-i);
                    }catch(ExecutionException ignored) {
                        // A timed-out optional discovery never invalidates good Peers.
                    }catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Dynamic Peer discovery interrupted",interrupted);
                    }
                    pendingRefresh=null;
                }
                // No long tail is allowed to strand already fsynced Pieces.
                // This preserves grouped manifest + DB CAS commits while
                // bounding progress latency and outstanding staged bytes.
                if(NativeGroupCommitPolicy.shouldPublish(checkpoint.stagedGroupSize(),
                        commitBatchSize,stagedWireBytes,stagedSinceNanos,
                        System.nanoTime(),false)) {
                    int count=checkpoint.stagedGroupSize();
                    flushGroup(stagedLastPiece,stagedWireBytes,listener,guard);
                    committed+=count;
                    stagedWireBytes=0;
                    stagedLastPiece=-1;
                    stagedSinceNanos=0;
                }
                Fetched fetched;
                try {
                    long waitMillis=NativeGroupCommitPolicy.maxPollMillis(
                            checkpoint.stagedGroupSize(),stagedSinceNanos,System.nanoTime());
                    Future<Fetched> finished=completed.poll(waitMillis,TimeUnit.MILLISECONDS);
                    if(finished==null)continue;
                    i++;
                    futures.remove(finished); // Do not retain completed Piece buffers.
                    fetched=finished.get();
                }catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Parallel Piece download interrupted",e);
                }catch(ExecutionException e) {
                    Throwable cause=e.getCause();
                    if(cause instanceof IOException io)throw io;
                    throw new IOException("Parallel Piece fetch failed",cause);
                }
                attempts+=fetched.attempts();
                if(fetched.bytes()==null) {
                    failed.add(fetched.piece());
                }else{
                    // Flush the previous group before admitting more durable work.
                    // The callback may transition RUNNING -> COMPLETED.
                    checkInterrupted();
                    NativeNetworkBudget.check();
                    guard.beforeNetworkRequest();
                    long stagedAt=System.nanoTime();
                    try {
                        checkpoint.stageGroupPiece(fetched.piece(),fetched.bytes(),
                                guard::beforeNetworkRequest);
                    }finally {checkpointCommitNanos.add(System.nanoTime()-stagedAt);}
                    if(stagedSinceNanos==0)stagedSinceNanos=System.nanoTime();
                    stagedWireBytes+=fetched.bytes().length;
                    stagedLastPiece=fetched.piece();
                }
                if(NativeGroupCommitPolicy.shouldPublish(checkpoint.stagedGroupSize(),
                        commitBatchSize,stagedWireBytes,stagedSinceNanos,
                        System.nanoTime(),i==scheduled.size())) {
                    int stagedCount=checkpoint.stagedGroupSize();
                    flushGroup(stagedLastPiece,stagedWireBytes,listener,guard);
                    committed+=stagedCount;
                    stagedWireBytes=0;
                    stagedLastPiece=-1;
                    stagedSinceNanos=0;
                }
                while(nextPiece<scheduled.size() && futures.size()<workers) {
                    checkInterrupted();
                    NativeNetworkBudget.check();
                    int piece=peerQueue==null?scheduled.get(nextPiece):-1;
                    futures.add(submitPiece(completed,piece,owner,health,guard,peerQueue));
                    nextPiece++;
                }
            }
            health.observeRound(scheduled.size(),committed);
            if(peerQueue!=null)
                org.slf4j.LoggerFactory.getLogger(PieceDownloadCoordinator.class).info(
                        "native_peer_centric_result assigned={} pending={} committed={} failed={}",
                        peerQueue.assigned(),peerQueue.remaining(),committed,failed.size());
            roundFinished=true;
            return result(scheduled.size(),committed,attempts,failed);
        }finally {
            if(pendingRefresh!=null)pendingRefresh.cancel(true);
            if(discovery!=null)discovery.shutdownNow();
            checkpoint.discardStagedGroup(); // Never publish a cancelled/failed partial group.
            for(Future<Fetched> f:futures)if(!f.isDone())f.cancel(true);
            if(!taskActors || !roundFinished)executor.shutdownNow();
            // A successfully finished task round retains idle actor threads and
            // their task-scoped connection affinity. An aborted round always
            // tears them down and is fenced from retrying until recovery.
            if(!taskActors || !roundFinished) {
            // Interrupt flags make awaitTermination throw immediately. Clear the flag
            // only while reaping children; restore it on the owning thread afterwards.
            boolean interrupted=Thread.interrupted();
            boolean terminated=false;
            long waitBudget=TimeUnit.MILLISECONDS.toNanos(Math.min(90_000L,
                    perPeerBudget.toMillis()*attemptsPerPiece+1000L));
            long end=System.nanoTime()+waitBudget;
            try {
                while(!terminated) {
                    long remaining=end-System.nanoTime();
                    if(remaining<=0)break;
                    try {
                        terminated=executor.awaitTermination(remaining,TimeUnit.NANOSECONDS);
                    }catch(InterruptedException e) {
                        interrupted=true;
                    }
                }
            }finally {
                if(interrupted)Thread.currentThread().interrupt();
            }
            if(!terminated)throw new IOException("Parallel Peer children have not stopped");
            if(interrupted)throw new IOException("Parallel Piece round interrupted during cleanup");
            }
        }
    }

    private void flushGroup(int lastPiece,int wireBytes,VerifiedCommitListener listener,
                            WorkerGuard guard) throws IOException {
        checkInterrupted();
        NativeNetworkBudget.check();
        guard.beforeNetworkRequest();
        long persistStarted=System.nanoTime();
        try {checkpoint.publishStagedGroup(guard::beforeNetworkRequest);}
        finally {checkpointCommitNanos.add(System.nanoTime()-persistStarted);}
        long casStarted=System.nanoTime();
        try {listener.afterCommit(lastPiece,wireBytes,checkpoint.verifiedSelectedBytes());}
        finally {progressCasNanos.add(System.nanoTime()-casStarted);}
    }

    private Future<Fetched> submitPiece(ExecutorCompletionService<Fetched> completed,
                                        int piece,Thread owner,PeerHealthTracker health,
                                        WorkerGuard guard,PeerCentricPieceQueue peerQueue) {
        Long roundDeadline=NativeNetworkBudget.currentDeadline();
        return completed.submit(()->{
            try(var budgetScope=NativeNetworkBudget.bind(roundDeadline)) {
                Thread child=Thread.currentThread();
                childThreads.register(owner,child);
                try {
                    if(owner.isInterrupted())throw new IOException("Native Worker was cancelled");
                    if(peerQueue==null)return fetchWithHealth(piece,health,guard,owner,null);
                    var assignment=peerQueue.claim(health,availability);
                    if(assignment==null)
                        throw new IOException("Peer actor scheduled without an available Piece");
                    return fetchWithHealth(assignment.piece(),health,guard,owner,assignment.peer());
                }finally {
                    childThreads.unregister(owner,child);
                }
            }
        });
    }

    private Fetched fetchWithHealth(int piece,PeerHealthTracker health,
                                    WorkerGuard guard,Thread owner,
                                    InetSocketAddress firstLease) throws IOException {
        Set<InetSocketAddress> tried=new HashSet<>();
        // Bounded in-memory partial Piece salvage: a disconnected Peer does not
        // force already received 16KiB blocks to be downloaded again.
        // Only the real task Peer session supports this; legacy mock fetchers
        // retain their existing API and behavior.
        var resumable=childThreads instanceof PeerPieceDownloadSession session?session:null;
        var assembler=resumable==null?null:new PieceBlockAssembler(planner,piece);
        int attempts=0;
        for(int i=0;i<attemptsPerPiece;i++) {
            checkInterrupted();
            NativeNetworkBudget.check();
            if(owner.isInterrupted())throw new IOException("Parent native Worker cancelled");
            InetSocketAddress endpoint=i==0 && firstLease!=null
                    ?firstLease:health.lease(piece,tried,availability);
            guard.beforeNetworkRequest();
            if(endpoint==null)break;
            tried.add(endpoint);
            attempts++;
            long fetchStarted=System.nanoTime();
            try {
                byte[] bytes=resumable!=null
                        ?resumable.downloadResumablePiece(endpoint,checkpoint.torrentInfo(),
                                peerId.clone(),planner,piece,
                                NativeNetworkBudget.cap(perPeerBudget),assembler)
                        :fetcher.fetch(endpoint,checkpoint.torrentInfo(),peerId.clone(),
                                planner,piece,NativeNetworkBudget.cap(perPeerBudget));
                NativeNetworkBudget.check();
                if(bytes==null || !PieceHashVerifier.verify(checkpoint.torrentInfo(),piece,bytes))
                    throw new IOException("Invalid SHA-1 from Peer");
                health.success(endpoint,bytes.length,System.nanoTime()-fetchStarted);
                return new Fetched(piece,bytes,attempts);
            }catch(IOException | RuntimeException peerFailure) {
                if(!(peerFailure instanceof PeerRoundConnectionPool.PieceUnavailableException))
                    health.failure(endpoint);
                NativeNetworkBudget.check();
                if(Thread.currentThread().isInterrupted() || owner.isInterrupted())
                    throw new IOException("Parallel Piece request was cancelled",peerFailure);
            }finally {
                peerFetchNanos.add(System.nanoTime()-fetchStarted);
                if(resumable!=null)
                    health.observeBlockRtt(endpoint,resumable.peerRttP95Nanos(endpoint));
                health.release(endpoint);
            }
        }
        return new Fetched(piece,null,attempts);
    }

    static int effectiveParallelWorkers(int configured,int scheduled,int pieceBytes) {
        if(configured<1 || configured>MAX_PARALLEL_PIECES || scheduled<1 || pieceBytes<1)
            throw new IllegalArgumentException("Invalid parallel Piece memory budget");
        long byMemory=Math.max(1L,MAX_IN_FLIGHT_PIECE_BYTES/pieceBytes);
        return (int)Math.min(Math.min((long)configured,scheduled),byMemory);
    }

    private static void checkInterrupted() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Piece round interrupted");
    }
    private RoundResult result(int attempted,int committed,int attempts,List<Integer> failed) {
        return new RoundResult(attempted,committed,attempts,failed,
                checkpoint.verifiedSelectedBytes(),checkpoint.selectedTotalBytes(),
                checkpoint.progressPercent());
    }
}
