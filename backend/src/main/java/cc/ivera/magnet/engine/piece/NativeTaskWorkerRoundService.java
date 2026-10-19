package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.BitSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Experimental P4 on-demand, bounded piece-work orchestration.
 *
 * Never invoked automatically: requires explicit internal authorization AND a
 * dedicated disabled-by-default feature gate. No HTTP "start" endpoint exists.
 * Discover peers only AFTER verified public metadata is reloaded from database.
 *
 * All task lifecycle writes are CAS-fenced by NativeTaskRoundRunner.
 */
public final class NativeTaskWorkerRoundService {
    @FunctionalInterface
    public interface PublicPeerFinder {
        List<InetSocketAddress> find(byte[] infoHash,byte[] peerId,int listenPort,
                                     VerifiedSwarmPrivacy.PublicPermit permit) throws IOException;
        /**
         * Inter-round refresh variant: implementations may consult slower sources
         * (DHT, PeX) that the first-round fast path skips when trackers already
         * returned enough candidates.
         */
        default List<InetSocketAddress> findThorough(byte[] infoHash,byte[] peerId,
                                                     int listenPort,
                                                     VerifiedSwarmPrivacy.PublicPermit permit)
                throws IOException {
            return find(infoHash,peerId,listenPort,permit);
        }
    }

    @FunctionalInterface
    public interface BitfieldProbe {
        Optional<BitSet> probe(InetSocketAddress peer,
                cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo torrent,
                byte[] peerId,Duration timeout) throws IOException;
    }

    public record RoundReport(int candidatePeers,boolean attempted,
                              PieceDownloadCoordinator.RoundResult download,
                              NativeTaskLifecycleService.TaskState task) {}

    private static final SecureRandom RANDOM=new SecureRandom();
    private static final org.slf4j.Logger PERF_LOG=
            org.slf4j.LoggerFactory.getLogger(NativeTaskWorkerRoundService.class);
    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
    }
    private final NativeTaskLifecycleService lifecycle;
    private final NativeTaskCheckpointFactory checkpointFactory;
    private final NativeTorrentProperties settings;
    private final PublicPeerFinder peerFinder;
    private final PieceDownloadCoordinator.Fetcher fetcher;
    private final TrackerAddressPolicy addressPolicy;
    private final PieceDownloadCoordinator.ChildThreads childThreads;
    private final BitfieldProbe bitfieldProbe;
    private volatile java.util.function.BooleanSupplier fencedHealth=()->false;
    private volatile String fencedInstanceId;
    private static final class ContinuousTask {
        final long owner;final UUID task;
        NativeTaskCheckpointFactory.OpenedPublicTask opened;
        byte[] peerId;
        NativeTaskLifecycleService.TaskState previous;
        boolean first=true;
        volatile long lastPeerRefreshNanos;
        ContinuousTask(long owner,UUID task){this.owner=owner;this.task=task;}
    }
    private final ThreadLocal<ContinuousTask> ongoing=new ThreadLocal<>();
    public record StagedProgress(int pieces,long selectedBytes) {}
    private final java.util.concurrent.ConcurrentHashMap<HealthKey,VerifiedPieceCheckpoint>
            activeCheckpointByTask=new java.util.concurrent.ConcurrentHashMap<>();
    public StagedProgress stagedProgress(long owner,UUID task) {
        if(owner<=0 || task==null)return new StagedProgress(0,0);
        var checkpoint=activeCheckpointByTask.get(new HealthKey(owner,task));
        return checkpoint==null?new StagedProgress(0,0):
                new StagedProgress(checkpoint.stagedGroupSize(),checkpoint.stagedSelectedBytes());
    }
    public void beginContinuousSession(long owner,UUID task) {
        if(owner<=0 || task==null || ongoing.get()!=null)
            throw new IllegalStateException("Invalid continuous task session");
        ongoing.set(new ContinuousTask(owner,task));
    }
    public void endContinuousSession() {
        var state=ongoing.get();
        if(state!=null)activeCheckpointByTask.remove(new HealthKey(state.owner,state.task));
        try {
            if(state!=null && childThreads instanceof PeerPieceDownloadSession peer)
                peer.endTask();
        }finally {ongoing.remove();}
    }
    public record PeerStatus(int poolPeers,int provenPeers,int degradedPeers,int refreshes) {}
    public synchronized PeerStatus peerStatus(long owner,UUID task) {
        if(owner<=0 || task==null)return new PeerStatus(0,0,0,0);
        var cached=peerHealthByTask.get(new HealthKey(owner,task));
        if(cached==null || System.nanoTime()-cached.recordedNanos()>
                java.util.concurrent.TimeUnit.MINUTES.toNanos(15))
            return new PeerStatus(0,0,0,0);
        var current=cached.health();
        return new PeerStatus(current.peerCount(),current.verifiedPeerCount(),
                current.degradedCount(),current.completedRefreshes());
    }
    private record HealthKey(long owner,UUID task) {}
    private record CachedHealth(PeerHealthTracker health,long recordedNanos) {}
    private final Map<HealthKey,CachedHealth> peerHealthByTask=new LinkedHashMap<>();
    private record DiscoveryKey(long owner,UUID task,String infoHash) {}
    private final Cache<DiscoveryKey,List<InetSocketAddress>> peerCandidatesByTask=Caffeine.newBuilder()
            .maximumSize(64).expireAfterWrite(Duration.ofMinutes(10)).build();
    private synchronized List<InetSocketAddress> provenPeersFor(long owner,UUID task) {
        var state=peerHealthByTask.get(new HealthKey(owner,task));
        if(state==null || System.nanoTime()-state.recordedNanos()>
                java.util.concurrent.TimeUnit.MINUTES.toNanos(15))
            return List.of();
        return state.health().provenPeers();
    }
    private synchronized PeerHealthTracker healthFor(long owner,UUID task,List<InetSocketAddress> endpoints) {
        long now=System.nanoTime();
        peerHealthByTask.entrySet().removeIf(e -> now-e.getValue().recordedNanos()>java.util.concurrent.TimeUnit.MINUTES.toNanos(15));
        var key=new HealthKey(owner,task);
        var previous=peerHealthByTask.get(key);
        var health=new PeerHealthTracker(endpoints,previous==null?null:previous.health());
        if(peerHealthByTask.size()>=64 && !peerHealthByTask.containsKey(key))
            peerHealthByTask.remove(peerHealthByTask.keySet().iterator().next());
        peerHealthByTask.put(key,new CachedHealth(health,now));
        return health;
    }

    public void configureFencedExecution(String instanceId,
                                         java.util.function.BooleanSupplier health) {
        if(instanceId==null || health==null)throw new IllegalArgumentException("Missing fence identity");
        this.fencedInstanceId=instanceId;
        this.fencedHealth=health;
    }

    public NativeTaskWorkerRoundService(
            NativeTaskLifecycleService lifecycle,
            NativeTaskCheckpointFactory checkpointFactory,
            NativeTorrentProperties settings,
            PublicPeerFinder peerFinder,
            PieceDownloadCoordinator.Fetcher fetcher,
            TrackerAddressPolicy addressPolicy) {
        this(lifecycle,checkpointFactory,settings,peerFinder,fetcher,addressPolicy,
                PieceDownloadCoordinator.ChildThreads.none(),(peer,info,id,timeout)->Optional.empty());
    }

    public NativeTaskWorkerRoundService(
            NativeTaskLifecycleService lifecycle,
            NativeTaskCheckpointFactory checkpointFactory,
            NativeTorrentProperties settings,
            PublicPeerFinder peerFinder,
            PieceDownloadCoordinator.Fetcher fetcher,
            TrackerAddressPolicy addressPolicy,
            PieceDownloadCoordinator.ChildThreads childThreads) {
        this(lifecycle,checkpointFactory,settings,peerFinder,fetcher,addressPolicy,
                childThreads,(peer,info,id,timeout)->Optional.empty());
    }

    public NativeTaskWorkerRoundService(
            NativeTaskLifecycleService lifecycle,
            NativeTaskCheckpointFactory checkpointFactory,
            NativeTorrentProperties settings,
            PublicPeerFinder peerFinder,
            PieceDownloadCoordinator.Fetcher fetcher,
            TrackerAddressPolicy addressPolicy,
            PieceDownloadCoordinator.ChildThreads childThreads,
            BitfieldProbe bitfieldProbe) {
        if(lifecycle==null || checkpointFactory==null || settings==null ||
                peerFinder==null || fetcher==null || addressPolicy==null ||
                childThreads==null || bitfieldProbe==null)
            throw new IllegalArgumentException("Missing native worker dependency");
        this.lifecycle=lifecycle;
        this.checkpointFactory=checkpointFactory;
        this.settings=settings;
        this.peerFinder=peerFinder;
        this.fetcher=fetcher;
        this.addressPolicy=addressPolicy;
        this.childThreads=childThreads;
        this.bitfieldProbe=bitfieldProbe;
    }

    /**
     * Bounded concurrent BEP3 Bitfield discovery. Each probe has its own TCP
     * session; timeouts and absent Bitfields mean UNKNOWN, never "has no Pieces".
     * The owner checks durable task revision before and after this method.
     */
    PeerPieceAvailability parallelBitfieldProbe(
            cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo torrent,
            byte[] peerId,List<InetSocketAddress> endpoints,int maxProbes,int timeoutMillis)
            throws IOException {
        int count=Math.min(maxProbes,endpoints.size());
        if(count==0)return new PeerPieceAvailability(torrent.pieceLayout().pieceCount(),Map.of());
        Duration perPeer=NativeNetworkBudget.cap(Duration.ofMillis(timeoutMillis));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(Math.min(8,count),r->{
            Thread t=new Thread(r,"clipfetch-bitfield-probe");
            t.setDaemon(true);
            return t;
        });
        java.util.List<java.util.concurrent.Callable<Map.Entry<InetSocketAddress,BitSet>>> requests=
                new java.util.ArrayList<>();
        for(int i=0;i<count;i++) {
            InetSocketAddress peer=endpoints.get(i);
            requests.add(()->{
                try {
                    Optional<BitSet> result=bitfieldProbe.probe(peer,torrent,peerId.clone(),perPeer);
                    return result.map(bits->Map.entry(peer,(BitSet)bits.clone())).orElse(null);
                }catch(IOException | IllegalArgumentException peerFailure) {
                    return null; // malformed or unavailable Peer: availability UNKNOWN
                }
            });
        }
        try {
            NativeNetworkBudget.check();
            var results=pool.invokeAll(requests,
                    Math.min(6000L,perPeer.toMillis()+800L),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            Map<InetSocketAddress,BitSet> reported=new LinkedHashMap<>();
            for(var future:results) {
                if(future.isCancelled())continue;
                try {
                    var found=future.get();
                    if(found!=null)reported.put(found.getKey(),found.getValue());
                }catch(java.util.concurrent.ExecutionException failure) {
                    Throwable cause=failure.getCause();
                    if(cause instanceof RuntimeException runtime)throw runtime;
                    if(cause instanceof Error severe)throw severe;
                    throw new IOException("Unexpected Bitfield probe failure",cause);
                }
            }
            NativeNetworkBudget.check();
            return new PeerPieceAvailability(torrent.pieceLayout().pieceCount(),reported);
        }catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Parallel Bitfield probing cancelled",interrupted);
        }finally {
            pool.shutdownNow();
            boolean interrupted=Thread.interrupted();
            try {
                if(!pool.awaitTermination(6,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IOException("Parallel Bitfield workers did not terminate");
            }catch(InterruptedException cancelled) {
                interrupted=true;
                throw new IOException("Parallel Bitfield shutdown cancelled",cancelled);
            }finally {
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * One synchronous, limited round, no background task and no seed uploads.
     * If no Peers are available, the task remains READY without false progress.
     *
     * No live worker can be started unless this experimental opt-in is true.
     */
    public RoundReport runOnce(long owner,UUID taskId,long expectedRevision,int maxPieces)
            throws IOException {
        int pieceLimit = settings != null ? Math.max(4, settings.getContinuousMaxPieces()) : 4;
        if(owner<=0 || taskId==null || expectedRevision<0 || maxPieces<1 || maxPieces>pieceLimit)
            throw new IllegalArgumentException("Invalid native worker request");
        if(!settings.isEnabled() || !settings.isWorkerRoundEnabled())
            throw new IllegalStateException("Native network worker is disabled");
        if(settings.getMetadataListenPort()<1 || settings.getMetadataListenPort()>65535 ||
                settings.getMaxPeersPerTask()<1 || settings.getMaxPeersPerTask()>256)
            throw new IllegalStateException("Invalid native worker network limits");
        int roundMillis=settings.getNetworkRoundBudgetMillis();
        if(roundMillis<1000 || roundMillis>600000)
            throw new IllegalStateException("Invalid native round network budget");
        try(var budget=NativeNetworkBudget.open(Duration.ofMillis(roundMillis))) {
        long roundStarted=System.nanoTime();
        NativeNetworkBudget.check();
        var initial=lifecycle.get(owner,taskId);
        if(!"READY".equals(initial.state()) || initial.revision()!=expectedRevision)
            throw new IllegalStateException("Task is not READY at the requested revision");

        // Only the trusted checkpoint factory can produce a permit for this task.
        long checkpointStarted=System.nanoTime();
        var ongoingSession=ongoing.get();
        if(ongoingSession!=null && (ongoingSession.owner!=owner ||
                !ongoingSession.task.equals(taskId)))
            throw new IllegalStateException("Continuous session belongs to another task");
        var opened=ongoingSession!=null && ongoingSession.opened!=null
                ?ongoingSession.opened:checkpointFactory.openPublicOwned(owner,taskId);
        long checkpointMillis=elapsedMillis(checkpointStarted);
        var checkpoint=opened.checkpoint();
        var permit=opened.permit();
        byte[] peerId=ongoingSession!=null && ongoingSession.peerId!=null
                ?ongoingSession.peerId.clone():new byte[20];
        if(ongoingSession==null || ongoingSession.peerId==null) {
            RANDOM.nextBytes(peerId);
            byte[] prefix="-CF0001-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(prefix,0,peerId,0,prefix.length);
        }
        byte[] hash=HexFormat.of().parseHex(permit.infoHash());
        var discoveryKey=new DiscoveryKey(owner,taskId,permit.infoHash());
        boolean keepPeers=false;
        boolean successfulRound=false;
        try {

        // First round verifies disk; subsequent rounds inside this one isolated
        // continuous job retain the verified checkpoint and check durable CAS.
        long reconcileStarted=System.nanoTime();
        var reconciled=ongoingSession!=null && !ongoingSession.first
                ?lifecycle.reconcileContinuing(owner,taskId,expectedRevision,checkpoint,
                        ongoingSession.previous)
                :lifecycle.reconcileFreshlyOpened(owner,taskId,expectedRevision,checkpoint);
        long reconcileMillis=elapsedMillis(reconcileStarted);
        if(ongoingSession!=null && ongoingSession.first) {
            ongoingSession.opened=opened;
            ongoingSession.peerId=peerId.clone();
            ongoingSession.first=false;
            if(childThreads instanceof PeerPieceDownloadSession peer)
                peer.beginTask(owner,taskId,permit.infoHash(),peerId,
                        checkpoint.torrentInfo().pieceLayout().pieceCount());
        }
        if(ongoingSession!=null)
            activeCheckpointByTask.put(new HealthKey(owner,taskId),checkpoint);
        // A crash may occur after final Piece checkpoint but before the last DB CAS.
        // Verified data is sufficient to finish without any further network traffic.
        if("COMPLETED".equals(reconciled.state())) {
            successfulRound=true;
            return new RoundReport(0,false,null,reconciled);
        }
        if(!"READY".equals(reconciled.state()))
            throw new IllegalStateException("Task state changed before discovery");

        long discoveryStarted=System.nanoTime();
        var peers=new PeerCandidatePool(settings.getMaxPeersPerTask(),addressPolicy);
        var cachedPeers=peerCandidatesByTask.getIfPresent(discoveryKey);
        var candidates=(cachedPeers!=null && !cachedPeers.isEmpty())?cachedPeers:
                peerFinder.find(hash,peerId,settings.getMetadataListenPort(),permit);
        // A validated inbound Peer may be re-used as a task-scoped download
        // connection; never advertise it to a different user or InfoHash.
        if(childThreads instanceof PeerPieceDownloadSession peerSession)
            for(var inbound:peerSession.inboundPeers(owner,taskId))
                peers.offer(inbound,PeerCandidatePool.Source.PUBLIC_TRACKER);
        // Retain known, SHA-1-proven Peers across Tracker re-announces.
        // A candidate is still revalidated against public-address policy.
        for(var proven:provenPeersFor(owner,taskId))
            peers.offer(proven,PeerCandidatePool.Source.PUBLIC_TRACKER);
        for(var candidate:candidates) {
            NativeNetworkBudget.check();
            if(Thread.currentThread().isInterrupted())
                throw new IOException("Native peer discovery interrupted");
            if(peers.size()>=settings.getMaxPeersPerTask())break;
            peers.offer(candidate,PeerCandidatePool.Source.PUBLIC_TRACKER);
        }
        // Pause/delete/revision change during discovery must not be overwritten.
        NativeNetworkBudget.check();
        var latest=lifecycle.get(owner,taskId);
        if(!"READY".equals(latest.state()) || latest.revision()!=reconciled.revision())
            throw new IllegalStateException("Task changed during Peer discovery");

        List<InetSocketAddress> endpoints=peers.snapshot().stream()
                .map(PeerCandidatePool.Candidate::address).toList();
        long discoveryMillis=elapsedMillis(discoveryStarted);
        if(endpoints.isEmpty()) {
            if(ongoingSession!=null)ongoingSession.previous=latest;
            successfulRound=true;
            PERF_LOG.info("native_perf stage=no_peers owner={} task={} openMs={} reconcileMs={} discoverMs={} totalMs={}",
                    owner,taskId,checkpointMillis,reconcileMillis,discoveryMillis,elapsedMillis(roundStarted));
            return new RoundReport(0,false,null,latest);
        }

        int parallel=settings.isParallelPieceEnabled()?settings.getParallelPieceWorkers():1;
        if(parallel<1 || parallel>PieceDownloadCoordinator.MAX_PARALLEL_PIECES)
            throw new IllegalStateException("Invalid parallel Piece Worker budget");
        long probeStarted=System.nanoTime();
        PeerPieceAvailability pieceAvailability=null;
        if(settings.isRarestFirstEnabled()) {
            int probes=settings.getBitfieldProbePeers();
            int timeoutMs=settings.getBitfieldProbeTimeoutMillis();
            if(probes<1 || probes>16 || timeoutMs<100 || timeoutMs>5000)
                throw new IllegalStateException("Invalid Bitfield probe limits");
            var current=lifecycle.get(owner,taskId);
            if(!"READY".equals(current.state()) || current.revision()!=latest.revision())
                throw new IllegalStateException("Task changed during Bitfield probing");
            pieceAvailability=parallelBitfieldProbe(checkpoint.torrentInfo(),peerId,
                    endpoints,probes,timeoutMs);
        }
        long probeMillis=elapsedMillis(probeStarted);
        NativeNetworkBudget.check();
        var postProbe=lifecycle.get(owner,taskId);
        if(!"READY".equals(postProbe.state()) || postProbe.revision()!=latest.revision())
            throw new IllegalStateException("Task changed before Piece scheduling");
        var coordinator=new PieceDownloadCoordinator(checkpoint,fetcher,endpoints,peerId,
                4, // Bound retries independently of the initial discovered Peer count;
                // Avoid dedicating a lane to unreachable/choked 256 KiB peers
                // for 16*15 seconds. Larger Piece sizes retain more time.
                NativeNetworkBudget.cap(Duration.ofSeconds(
                        checkpoint.torrentInfo().pieceLength()>1024*1024
                                ?15:8)),parallel,
                childThreads,pieceAvailability);
        var roundHealth=healthFor(owner,taskId,endpoints);
        coordinator.useRoundHealth(roundHealth);
        if(ongoingSession!=null && parallel>1 &&
                settings.isPeerConnectionReuseEnabled() &&
                settings.isMultiplexedPeerMailboxEnabled())
            coordinator.enableMultiplexedMailbox();
        if(ongoingSession!=null) {
            var taskSession=ongoingSession;
            var verifiedPermit=permit;
            byte[] scopedHash=hash.clone(),scopedPeerId=peerId.clone();
            coordinator.enablePeerRefresh(()->{
                long now=System.nanoTime();
                long last=taskSession.lastPeerRefreshNanos;
                if(last!=0 && now-last<java.util.concurrent.TimeUnit.SECONDS.toNanos(15))
                    return List.of(); // bounded Tracker/DHT refresh, faster on high-failure swarms
                taskSession.lastPeerRefreshNanos=now;
                NativeNetworkBudget.check();
                var refreshed=new java.util.ArrayList<>(
                        peerFinder.findThorough(scopedHash.clone(),scopedPeerId.clone(),
                                settings.getMetadataListenPort(),verifiedPermit));
                if(childThreads instanceof PeerPieceDownloadSession peerSession)
                    refreshed.addAll(peerSession.inboundPeers(owner,taskId));
                var vetted=new PeerCandidatePool(settings.getMaxPeersPerTask(),addressPolicy);
                for(var next:refreshed) {
                    NativeNetworkBudget.check();
                    vetted.offer(next,PeerCandidatePool.Source.PUBLIC_TRACKER);
                }
                return vetted.snapshot().stream().map(PeerCandidatePool.Candidate::address).toList();
            },settings.getMaxPeersPerTask());
        }
        if(parallel>1)coordinator.configureGroupCommit(settings.getCommitGroupPieces());
        if(!settings.isInstanceHeartbeatEnabled() && !settings.isLegacyWorkerCompatibilityEnabled())
            throw new IllegalStateException("Unfenced Native Piece execution is disabled");
        var runner=new NativeTaskRoundRunner(lifecycle);
        long transferStarted=System.nanoTime();
        var execution=settings.isInstanceHeartbeatEnabled()
                ?runner.runFenced(owner,taskId,latest.revision(),checkpoint,coordinator,
                        maxPieces,fencedInstanceId,fencedHealth)
                :runner.run(owner,taskId,latest.revision(),checkpoint,coordinator,maxPieces);
        var perf=coordinator.performance();
        PERF_LOG.info("native_perf owner={} task={} candidates={} peerPoolEnd={} degraded={} workerSlots={} committed={} attempted={} peerAttempts={} failedPieces={} "
                + "openMs={} reconcileMs={} discoverMs={} probeMs={} transferWallMs={} "
                + "peerFetchAccumulatedMs={} diskCommitMs={} progressCasMs={} totalMs={}",
                owner,taskId,endpoints.size(),roundHealth.peerCount(),roundHealth.degradedCount(),
                perf.workerSlots(),execution.round().committedPieces(),
                execution.round().attemptedPieces(),execution.round().peerAttempts(),
                execution.round().failedPieces().size(),
                checkpointMillis,reconcileMillis,discoveryMillis,probeMillis,elapsedMillis(transferStarted),
                perf.peerFetchMillis(),perf.commitMillis(),perf.databaseMillis(),elapsedMillis(roundStarted));
        if(ongoingSession!=null)ongoingSession.previous=execution.task();
        keepPeers=execution.round().committedPieces()>0 && !"COMPLETED".equals(execution.task().state());
        if(keepPeers)
            peerCandidatesByTask.put(discoveryKey,roundHealth.snapshotPeers());
        successfulRound=true;
        return new RoundReport(endpoints.size(),true,execution.round(),execution.task());
        }finally {
            if(!successfulRound && ongoingSession!=null) {
                activeCheckpointByTask.remove(new HealthKey(owner,taskId));
                ongoingSession.opened=null;
                ongoingSession.previous=null;
                ongoingSession.peerId=null;
                ongoingSession.first=true;
                if(childThreads instanceof PeerPieceDownloadSession peer)peer.endTask();
            }
            if(!keepPeers)peerCandidatesByTask.invalidate(discoveryKey);
        }
        }
    }
}
