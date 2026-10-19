package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bounded experimental multi-Piece TCP wire runner. Only known authorized
 * public task sessions can call this; owner thread commits verified Pieces.
 * Production uses this solely behind the disabled-by-default feature flag.
 */
final class PeerMultiplexedRound {
    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(PeerMultiplexedRound.class);
    record Download(Map<Integer,byte[]> verified,int openedPeers,int failedPeers,
                    long transferredBlocks,long lateBlocksDropped,
                    long actorPeerReassignments,long retiredPeerSockets,
                    int targetPeerActors,int activePeerActors) {
        Download {verified=Map.copyOf(verified);}
        // Legacy constructor for backward compatibility during transition
        Download(Map<Integer,byte[]> verified,int openedPeers,int failedPeers,
                long transferredBlocks,long lateBlocksDropped) {
            this(verified,openedPeers,failedPeers,transferredBlocks,lateBlocksDropped,0,0,0,0);
        }
    }

    @FunctionalInterface
    interface VerifiedPieceConsumer {
        void accepted(int piece,byte[] verifiedBytes) throws IOException;
        default boolean idle() throws IOException {return false;}
        default boolean isBusy() { return false; }
    }

    static List<InetSocketAddress> prioritizeCachedPeers(List<InetSocketAddress> peers,
                                                          List<InetSocketAddress> cached) {
        if(peers==null || cached==null)throw new IllegalArgumentException("Missing Peer list");
        var cachedByKey=new LinkedHashMap<String,InetSocketAddress>();
        for(var peer:cached)cachedByKey.putIfAbsent(peerKey(peer),peer);
        var ordered=new ArrayList<InetSocketAddress>(peers.size()+cachedByKey.size());
        var seen=new HashSet<String>();
        for(var peer:peers) {
            String key=peerKey(peer);
            if(cachedByKey.containsKey(key) && seen.add(key)) {
                ordered.add(peer);
                cachedByKey.remove(key);
            }
        }
        for(var peer:cachedByKey.values())
            if(seen.add(peerKey(peer)))ordered.add(peer);
        for(var peer:peers)
            if(seen.add(peerKey(peer)))ordered.add(peer);
        return List.copyOf(ordered);
    }

    private static String peerKey(InetSocketAddress peer) {
        if(peer==null)throw new IllegalArgumentException("Missing Peer endpoint");
        var host=peer.getAddress()==null?peer.getHostString():peer.getAddress().getHostAddress();
        return host.toLowerCase(Locale.ROOT)+":"+peer.getPort();
    }

    /**
     * Compute target Actor count for the supervisor. Without verified Peers,
     * use exploration slots only. With verified Peers, scale up to available
     * proven Peers plus bounded unknown exploration.
     */
    static int computeTargetActors(PeerHealthTracker tracker,int explorationSlots,int maxActors) {
        if(tracker==null || explorationSlots<1 || maxActors<1)
            throw new IllegalArgumentException("Invalid target Actor calculation");
        int verified=tracker.verifiedPeerCount();
        if(verified==0)return Math.min(explorationSlots,maxActors);
        // Verified Peers prove throughput; the unverified pool still hides
        // live Peers because public swarms routinely list >80% dead endpoints.
        // Keep every verified slot productive plus the bounded exploration
        // share instead of pinning discovery to verified+4.
        return Math.min(verified+explorationSlots,maxActors);
    }

    static Download fetch(Thread owner,PeerPieceDownloadSession session,TorrentInfo info,
                          PieceBlockPlanner planner,List<Integer> pieces,
                          List<InetSocketAddress> peers,byte[] peerId,
                          PeerPieceAvailability availability,int maxActors,
                          Duration timeout,PieceDownloadCoordinator.WorkerGuard guard)
            throws IOException {
        return fetch(owner,session,info,planner,pieces,peers,peerId,availability,
                maxActors,timeout,guard,(piece,bytes)->{});
    }

    static Download fetch(Thread owner,PeerPieceDownloadSession session,TorrentInfo info,
                          PieceBlockPlanner planner,List<Integer> pieces,
                          List<InetSocketAddress> peers,byte[] peerId,
                          PeerPieceAvailability availability,int maxActors,
                          Duration timeout,PieceDownloadCoordinator.WorkerGuard guard,
                          VerifiedPieceConsumer onVerified)
            throws IOException {
        return fetch(owner,session,info,planner,pieces,peers,peerId,availability,
                maxActors,timeout,guard,onVerified,null);
    }

    /**
     * Health-aware persistent Actor supervisor. Actors are not fixed to Peers;
     * when a Peer session ends (success or failure), the Actor immediately
     * leases another available Peer from the health tracker.
     */
    static Download fetch(Thread owner,PeerPieceDownloadSession session,TorrentInfo info,
                          PieceBlockPlanner planner,List<Integer> pieces,
                          List<InetSocketAddress> peers,byte[] peerId,
                          PeerPieceAvailability availability,int maxActors,
                          Duration timeout,PieceDownloadCoordinator.WorkerGuard guard,
                          VerifiedPieceConsumer onVerified,PeerHealthTracker tracker)
            throws IOException {
        if(onVerified==null || owner==null || session==null || info==null || planner==null ||
                pieces==null || pieces.isEmpty() || peers==null || peers.isEmpty() ||
                peerId==null || peerId.length!=20 || availability==null ||
                maxActors<1 || timeout==null || guard==null || !session.actorScope(owner))
            throw new IllegalArgumentException("Invalid multiplexed task");
        long bytes=0;
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        var assemblers=new HashMap<Integer,PieceBlockAssembler>();
        for(int piece:pieces) {
            if(assemblers.containsKey(piece))
                throw new IllegalArgumentException("Duplicate Piece in mailbox round");
            int length=planner.pieceBytes(piece);
            bytes+=length;
            if(bytes>512L*1024*1024)
                throw new IllegalArgumentException("Unbounded multi-Piece buffering");
            assemblers.put(piece,new PieceBlockAssembler(planner,piece));
            for(var block:planner.blocks(piece))
                keys.add(new PeerBlockMailbox.Key(piece,block.begin(),block.length()));
        }
        var mailbox=new PeerBlockMailbox(keys);
        var ready=new ConcurrentHashMap<Integer,byte[]>();
        // Network actors only publish verified results into this bounded
        // queue. The owner thread drains it and performs ALL durable commits
        // while other actors continue filling their cross-Piece pipelines.
        var verifiedEvents=new ArrayBlockingQueue<Integer>(pieces.size());
        var cancellations=new ConcurrentHashMap<InetSocketAddress,
                ConcurrentLinkedQueue<PeerBlockMailbox.Request>>();
        var orderedPeers=prioritizeCachedPeers(peers,session.idlePeers(owner));
        var executor=session.borrowActors(owner,Math.min(maxActors,orderedPeers.size()));
        var futures=new ArrayList<Future<?>>();
        int actors=Math.min(maxActors,orderedPeers.size());
        // Health tracker: use external one if provided, else create round-local
        var health=tracker!=null?tracker:new PeerHealthTracker(orderedPeers);
        var failedThisRound=ConcurrentHashMap.<InetSocketAddress>newKeySet();
        var reassignments=new java.util.concurrent.atomic.AtomicLong();
        var retiredSockets=new java.util.concurrent.atomic.AtomicLong();
        var activeActors=new java.util.concurrent.atomic.AtomicInteger();
        var lastActivityNanos=new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        // Failure causes were previously swallowed: every Peer error only
        // bumped a counter. Without the breakdown a sustained zero-throughput
        // round is indistinguishable from a genuinely empty swarm.
        var failureCauses=new ConcurrentHashMap<
                String,java.util.concurrent.atomic.AtomicInteger>();
        var noWorkWaits=new java.util.concurrent.atomic.AtomicInteger();
        Long roundDeadline=NativeNetworkBudget.currentDeadline();
        try {
            // Actor supervisor loop: each Actor continuously leases Peers
            for(int i=0;i<actors;i++) {
                futures.add(executor.submit(()->{
                    try(var budgetScope=NativeNetworkBudget.bind(roundDeadline)) {
                        Thread child=Thread.currentThread();
                        session.registerChild(owner,child);
                        try {
                            if(owner.isInterrupted())throw new IOException("Task has been cancelled");
                            // Actor loop: lease Peer, run mailbox, release, repeat
                            while(mailbox.remaining()>0 && !mailbox.closedForClaiming() &&
                                !Thread.currentThread().isInterrupted() && !owner.isInterrupted()) {
                            // Lease a Peer that could serve any remaining Piece.
                            // Mutual exclusion is enforced via inFlight==0.
                            Set<Integer> remaining=mailbox.remainingPieces();
                            InetSocketAddress endpoint=health.leaseExclusive(
                                    remaining,failedThisRound,availability);
                            if(endpoint==null) {
                                noWorkWaits.incrementAndGet();
                                // Every useful Peer can be temporarily leased or cooling
                                // while task-owned Tracker/DHT/PeX refresh is still in
                                // flight. Keep the Actor available briefly so a refreshed Peer
                                // or cooled Peer can be leased immediately.
                                try {Thread.sleep(50);}
                                catch(InterruptedException stopped) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException("Mailbox Actor interrupted while waiting for Peer",stopped);
                                }
                                // If the entire swarm has been completely idle (no active actors)
                                // and quiet for over 3 seconds with no pending background refresh,
                                // exit the waiting actor so the round can drain gracefully instead of
                                // stalling until the 120s deadline.
                                if(activeActors.get()==0 && !onVerified.isBusy() &&
                                        (System.nanoTime()-lastActivityNanos.get())>=TimeUnit.SECONDS.toNanos(3)) {
                                    break;
                                }
                                continue;
                            }
                            activeActors.incrementAndGet();
                            lastActivityNanos.set(System.nanoTime());
                            var sessionBytes=new java.util.concurrent.atomic.AtomicLong();
                            long sessionStarted=System.nanoTime();
                            try {
                                session.runMailbox(endpoint,info,peerId.clone(),planner,mailbox,
                                        availability,cancellations,winner->{
                                            lastActivityNanos.set(System.nanoTime());
                                            byte[] payload=winner.payload();
                                            sessionBytes.addAndGet(payload.length);
                                            long rtt=mailbox.lastRttNanos(endpoint);
                                            if(rtt>0)health.observeBlockRtt(endpoint,rtt);
                                            int piece=winner.key().piece();
                                            var assembler=assemblers.get(piece);
                                            if(assembler==null)
                                                throw new IOException("Unexpected Piece from Peer mailbox");
                                            synchronized(assembler) {
                                                try {
                                                    assembler.accept(piece,winner.key().begin(),
                                                            payload);
                                                    if(assembler.isComplete()) {
                                                        byte[] validated=assembler.verifiedPiece(info);
                                                        if(ready.putIfAbsent(piece,validated)==null &&
                                                                !verifiedEvents.offer(piece))
                                                            throw new IOException(
                                                                    "Verified Piece delivery queue is full");
                                                    }
                                                }catch(IllegalArgumentException invalid) {
                                                    throw new IOException("Multi-Piece SHA-1 or block invalid",invalid);
                                                }
                                            }
                                        },guard,timeout);
                                long elapsed=System.nanoTime()-sessionStarted;
                                long totalBytes=sessionBytes.get();
                                if(totalBytes>0 && elapsed>0) {
                                    health.success(endpoint,totalBytes,elapsed);
                                } else {
                                    health.success(endpoint);
                                }
                            }catch(IOException peerError) {
                                failureCauses.computeIfAbsent(peerFailureCause(peerError),
                                        cause->new java.util.concurrent.atomic.AtomicInteger())
                                        .incrementAndGet();
                                failedThisRound.add(endpoint);
                                health.failure(endpoint);
                                reassignments.incrementAndGet();
                            }finally {
                                activeActors.decrementAndGet();
                                health.release(endpoint);
                            }
                        }
                    }finally {session.unregisterChild(owner,child);}
                    return null;
                }
            }));
            }
            long deadline=System.nanoTime()+timeout.toNanos()+TimeUnit.SECONDS.toNanos(3);
            long drainDeadline=0L;
            var pending=new HashSet<Future<?>>(futures);
            while(!pending.isEmpty() || !verifiedEvents.isEmpty()) {
                if(owner.isInterrupted())
                    throw new IOException("Multi-Piece owner cancelled");
                long now=System.nanoTime();
                if(drainDeadline==0L && !pending.isEmpty()) {
                    boolean timedOut=now>=deadline;
                    boolean quietStall=activeActors.get()==0 && verifiedEvents.isEmpty() &&
                            !onVerified.isBusy() && (now-lastActivityNanos.get())>=TimeUnit.SECONDS.toNanos(3);
                    if(timedOut || quietStall) {
                        // Either deadline reached or swarm completely stalled with no active transfers:
                        // stop issuing NEW block claims and give Actors a brief grace window to finish.
                        mailbox.closeForClaiming();
                        drainDeadline=now+(quietStall?TimeUnit.MILLISECONDS.toNanos(500):TimeUnit.SECONDS.toNanos(15));
                    }
                }
                if(drainDeadline>0L && now>=drainDeadline && !pending.isEmpty()) {
                    session.abortChildren(owner);
                    for(var future:pending)future.cancel(true);
                    pending.clear();
                    Integer piece;
                    while((piece=verifiedEvents.poll())!=null) {
                        byte[] verified=ready.get(piece);
                        if(verified!=null) {
                            guard.beforeNetworkRequest();
                            onVerified.accepted(piece,verified.clone());
                        }
                    }
                    break;
                }
                try {
                    Integer piece=verifiedEvents.poll(50,TimeUnit.MILLISECONDS);
                    if(piece!=null) {
                        lastActivityNanos.set(System.nanoTime());
                        byte[] verified=ready.get(piece);
                        if(verified==null)
                            throw new IOException("Missing verified Piece result");
                        // Never execute DB CAS or Checkpoint publication on an
                        // untrusted network child; only the job owner drains.
                        guard.beforeNetworkRequest();
                        onVerified.accepted(piece,verified.clone());
                    }else if(onVerified.idle()) {
                        lastActivityNanos.set(System.nanoTime());
                        if(drainDeadline>0L) {
                            drainDeadline=0L;
                            mailbox.reopenForClaiming();
                        }
                    }
                    for(var future:List.copyOf(pending)) {
                        if(!future.isDone())continue;
                        pending.remove(future);
                        try {future.get();}
                        catch(ExecutionException failed) {
                            if(owner.isInterrupted())
                                throw new IOException("Multi-Piece owner cancelled",failed);
                        }catch(CancellationException stopped) {
                            // Actor was cancelled
                        }
                    }
                }catch(InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Multi-Piece owner interrupted",stopped);
                }
            }
            if(!failedThisRound.isEmpty() || noWorkWaits.get()>0)
                logFailureDiagnostics(actors,failedThisRound.size(),noWorkWaits.get(),failureCauses);
            // A round with no verified Piece means the swarm is churning or the
            // whole pool is cooling down. Decay accumulated failures so a
            // recovered or newly discovered Peer is not locked out by an
            // earlier round: otherwise the task spins in zero-throughput rounds
            // until the 15-minute cross-round health record expires.
            if(ready.isEmpty())health.grantSwarmAmnesty();
            int targetActors=computeTargetActors(health,Math.min(32,maxActors),maxActors);
            session.setActorMetrics(owner,targetActors,actors,reassignments.get());
            return new Download(ready,actors,failedThisRound.size(),mailbox.timedOutTransfers(),
                    mailbox.staleResponses(),reassignments.get(),retiredSockets.get(),
                    targetActors,actors);
        }finally {
            for(var future:futures)
                if(!future.isDone())future.cancel(true);
            if(owner.isInterrupted())session.abortForThread(owner);
        }
    }

    private static void logFailureDiagnostics(int actors,int failedPeers,int noWorkWaits,
                                              Map<String,java.util.concurrent.atomic.AtomicInteger> causes) {
        var summary=new StringBuilder();
        causes.entrySet().stream()
                .sorted((left,right)->Integer.compare(right.getValue().get(),
                        left.getValue().get()))
                .limit(6)
                .forEach(entry->{
                    if(summary.length()>0)summary.append(',');
                    summary.append(entry.getKey()).append('=').append(entry.getValue().get());
                });
        LOG.info("native_multiplexed_diagnostics actors={} failedPeers={} noWorkWaits={} causes={}",
                actors,failedPeers,noWorkWaits,summary);
    }

    /** Collapse a Peer transport failure into a short, comparable category. */
    private static String peerFailureCause(IOException failure) {
        if(failure==null)return "unknown";
        if(NativeNetworkBudget.deadlineExceeded(failure))return "budgetDeadline";
        // The JDK socket layer reports OS errors with a localized message, so
        // classify by type first and only fall back to our own English literals.
        if(failure instanceof java.net.SocketTimeoutException)return "timeout";
        if(failure instanceof java.net.NoRouteToHostException)return "noroute";
        if(failure instanceof java.net.ConnectException)return "connect";
        if(failure instanceof java.net.SocketException)return "socketAbort";
        String message=failure.getMessage();
        if(message==null || message.isBlank())return failure.getClass().getSimpleName();
        String lower=message.toLowerCase(Locale.ROOT);
        if(lower.contains("choked"))return "choked";
        if(lower.contains("unchoke"))return "unchoke";
        if(lower.contains("disconnect") || lower.contains("connection reset")
                || lower.contains("broken pipe") || lower.contains("eof"))return "eof";
        if(lower.contains("stall"))return "stall";
        if(lower.contains("cancel") || lower.contains("interrupt"))return "cancelled";
        if(lower.contains("handshake") || lower.contains("identity"))return "handshake";
        if(lower.contains("bitfield") || lower.contains("frame")
                || lower.contains("malformed"))return "protocol";
        return message.length()<=40?message:message.substring(0,40);
    }
}
