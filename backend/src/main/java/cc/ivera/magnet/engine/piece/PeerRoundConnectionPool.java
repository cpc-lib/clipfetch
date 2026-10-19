package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task-round-scoped BEP3 sockets. A network worker thread exclusively owns a
 * connection; it can download multiple Pieces through the same handshake.
 * No socket survives a round or crosses workers, users or InfoHashes.
 *
 * Up to four cached Peers per worker thread; parallel piece threads are independently
 * bounded to at most 64 by the coordinator. No sockets survive a round.
 * All I/O is deadline-bounded and externally cancellable via closing sockets.
 */
final class PeerRoundConnectionPool {
    private static final java.util.logging.Logger DIAGNOSTICS=
            java.util.logging.Logger.getLogger(PeerRoundConnectionPool.class.getName());
    /** A valid Bitfield-negative response is not a failed TCP session. */
    static final class PieceUnavailableException extends IOException {
        PieceUnavailableException() {super("Peer does not advertise requested Piece");}
    }
    private static final int PER_THREAD_CAP=4;
    private static final long IDLE_NANOS=Duration.ofSeconds(30).toNanos();
    /**
     * Cross-round keepalive budget is based on task-local Block RTT and proven
     * socket reuse, not the global Peer count. Slow tail connections retire
     * early, productive reused sockets survive one slower tracker round.
     * Closed/stale TCP sockets are still dropped on their next I/O failure.
     */
    static long cacheLifetimeNanos(long peerP95Nanos,long successfulReuses) {
        if(peerP95Nanos>=Duration.ofMillis(800).toNanos())
            return Duration.ofSeconds(15).toNanos();
        if(peerP95Nanos>0 && peerP95Nanos<=Duration.ofMillis(350).toNanos()
                && successfulReuses>0)
            return Duration.ofSeconds(75).toNanos();
        return IDLE_NANOS;
    }
    private static long cacheLifetimeNanos(TaskIdle task,Connection link) {
        if(task==null)return IDLE_NANOS;
        var endpoint=InetSocketAddress.createUnresolved(link.key.address,link.key.port);
        var latency=task.peerLatencies.get(endpoint);
        return cacheLifetimeNanos(latency==null?0:latency.p95Nanos(),
                task.reuseHits.sum()+task.crossRoundReuseHits.sum());
    }
    private record Key(String address,int port,String hash,String peerId) {}
    private static final class Connection {
        final Key key;
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        boolean unchoked;
        boolean inbound;
        byte[] advertised;
        int advertisedRevision;
        long lastUsed;
        Connection(Key key,Socket socket,InputStream in,OutputStream out) {
            this.key=key;this.socket=socket;this.in=in;this.out=out;
            this.lastUsed=System.nanoTime();
        }
    }

    private final PeerWireCodec codec=new PeerWireCodec();
    private final TrackerAddressPolicy policy;
    private final Set<Thread> roundOwners=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Thread,Set<Thread>> roundMembers=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread,Thread> childParents=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread,ConcurrentHashMap<Key,Connection>> connections=
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread,Socket> connecting=new ConcurrentHashMap<>();
    private static final int TASK_IDLE_CAP=256;
    private static final class TaskIdle {
        final long owner; final UUID task; final String hash;
        final byte[] localPeerId;
        final int pieceCount;
        final Set<InetSocketAddress> inboundPeers=ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.LongAdder receivedPayload=new java.util.concurrent.atomic.LongAdder();
        final NativePeerRequestTelemetry requestTelemetry=new NativePeerRequestTelemetry();
        final ConcurrentHashMap<InetSocketAddress,PeerLatencyWindow> peerLatencies=
                new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.LongAdder openedSockets=new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder reuseHits=new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder crossRoundReuseHits=new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder retiredPeerSockets=new java.util.concurrent.atomic.LongAdder();
        volatile int targetPeerActors;
        volatile int activePeerActors;
        volatile long actorPeerReassignments;
        final ConcurrentHashMap<Key,Connection> idle=new ConcurrentHashMap<>();
        TaskIdle(long owner,UUID task,String hash,byte[] localPeerId,int pieceCount) {
            this.owner=owner;this.task=task;this.hash=hash;
            this.localPeerId=localPeerId.clone();this.pieceCount=pieceCount;
        }
    }
    // Job thread is the exclusive lifetime owner; task sockets are never shared
    // between different users, tasks or hashes even when their IPs coincide.
    private final ConcurrentHashMap<Thread,TaskIdle> taskScopes=new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong connects=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong closes=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong ioFailures=new java.util.concurrent.atomic.AtomicLong();
    record Counters(long connected,long closed,long ioFailures,int registered) {}
    Counters counters() {
        return new Counters(connects.get(),closes.get(),ioFailures.get(),registeredSocketCount());
    }
    private final int handshakeTimeoutMillis;
    private final int stallTimeoutMillis;
    private final int connectTimeoutMillis;

    PeerRoundConnectionPool(TrackerAddressPolicy policy, int handshakeTimeoutMillis,
                            int stallTimeoutMillis, int connectTimeoutMillis) {
        this.policy=Objects.requireNonNull(policy);
        this.handshakeTimeoutMillis=Math.max(500, handshakeTimeoutMillis);
        this.stallTimeoutMillis=Math.max(500, stallTimeoutMillis);
        this.connectTimeoutMillis=Math.max(200, connectTimeoutMillis);
    }
    PeerRoundConnectionPool(TrackerAddressPolicy policy) {
        this(policy, 2000, 8000, 1500);
    }

    /** Snapshot of cached sockets and in-flight connections; excludes direct sessions. */
    int registeredSocketCount() {
        int count=connecting.size();
        for(var pool:connections.values())count+=pool.size();
        for(var task:taskScopes.values())count+=task.idle.size();
        return count;
    }

    /** Only Piece payload bytes accepted from the socket, not verified disk data. */
    long receivedPayloadBytes(long userId,UUID taskId) {
        if(userId<=0 || taskId==null)return 0;
        for(var scope:taskScopes.values())
            if(scope.owner==userId && taskId.equals(scope.task))
                return scope.receivedPayload.sum();
        return 0;
    }
    record TaskConnectionStats(long openedSockets,long reuseHits,long crossRoundReuseHits,
                               int idleSockets,long retiredPeerSockets,
                               int targetPeerActors,int activePeerActors,
                               long actorPeerReassignments) {}
    TaskConnectionStats taskConnectionStats(long owner,UUID task) {
        if(owner<=0 || task==null)return new TaskConnectionStats(0,0,0,0,0,0,0,0);
        for(var scope:taskScopes.values()) {
            if(scope.owner==owner && scope.task.equals(task))
                return new TaskConnectionStats(scope.openedSockets.sum(),
                        scope.reuseHits.sum(),scope.crossRoundReuseHits.sum(),
                        scope.idle.size(),scope.retiredPeerSockets.sum(),
                        scope.targetPeerActors,scope.activePeerActors,
                        scope.actorPeerReassignments);
        }
        return new TaskConnectionStats(0,0,0,0,0,0,0,0);
    }
    /** Update the Actor supervisor metrics for the owner's active task scope. */
    void setActorMetrics(Thread owner,int targetActors,int activeActors,long reassignments) {
        if(owner==null)return;
        TaskIdle scope=taskScopes.get(owner);
        if(scope==null)return;
        scope.targetPeerActors=targetActors;
        scope.activePeerActors=activeActors;
        scope.actorPeerReassignments=reassignments;
    }
    List<InetSocketAddress> idlePeers(Thread owner) {
        if(owner==null)return List.of();
        Thread parent=childParents.getOrDefault(owner,owner);
        TaskIdle task=taskScopes.get(parent);
        if(task==null)return List.of();
        return task.idle.keySet().stream()
                .map(key->InetSocketAddress.createUnresolved(key.address,key.port))
                .toList();
    }
    NativePeerRequestTelemetry.Snapshot requestTelemetry(long owner,UUID task) {
        if(owner<=0 || task==null)return new NativePeerRequestTelemetry().snapshot();
        for(var scope:taskScopes.values())
            if(scope.owner==owner && scope.task.equals(task))
                return scope.requestTelemetry.snapshot();
        return new NativePeerRequestTelemetry().snapshot();
    }
    void recordRequestWindow(Thread actor,InetSocketAddress peer,
                             PeerBlockRequestWindow window,int requestsSent,
                             boolean acknowledged) {
        Thread parent=childParents.getOrDefault(actor,actor);
        TaskIdle task=taskScopes.get(parent);
        if(task==null)return;
        long observed=acknowledged?window.lastObservedRttNanos():0;
        task.requestTelemetry.observe(actor,window.inFlight(),
                Math.max(window.inFlight(),window.targetOutstanding()),
                requestsSent,acknowledged,observed);
        if(acknowledged && observed>0 && peer!=null) {
            var stable=InetSocketAddress.createUnresolved(peer.getHostString(),peer.getPort());
            task.peerLatencies.computeIfAbsent(stable,p->new PeerLatencyWindow()).add(observed);
        }
    }
    long peerRttP95Nanos(Thread actor,InetSocketAddress peer) {
        Thread parent=childParents.getOrDefault(actor,actor);
        TaskIdle task=taskScopes.get(parent);
        if(task==null || peer==null)return 0;
        PeerLatencyWindow samples=task.peerLatencies.get(
                InetSocketAddress.createUnresolved(peer.getHostString(),peer.getPort()));
        return samples==null?0:samples.p95Nanos();
    }
    void recordMailboxWindow(Thread actor,int pending,int capacity,
                             int newRequests,boolean acknowledged,long rttNanos) {
        Thread parent=childParents.getOrDefault(actor,actor);
        TaskIdle task=taskScopes.get(parent);
        if(task!=null)task.requestTelemetry.observe(actor,
                pending,Math.max(pending,capacity),newRequests,acknowledged,rttNanos);
    }
    void clearRequestWindow(Thread actor) {
        Thread parent=childParents.getOrDefault(actor,actor);
        TaskIdle task=taskScopes.get(parent);
        if(task!=null)task.requestTelemetry.leave(actor);
    }
    void recordReceivedPayload(Thread networkThread,int bytes) {
        if(bytes<=0)return;
        Thread parent=childParents.getOrDefault(networkThread,networkThread);
        TaskIdle scope=taskScopes.get(parent);
        if(scope!=null)scope.receivedPayload.add(bytes);
    }
    void beginTask(Thread owner,long userId,UUID taskId,String hash) {
        beginTask(owner,userId,taskId,hash,new byte[20],0);
    }
    void beginTask(Thread owner,long userId,UUID taskId,String hash,byte[] localPeerId,
                   int pieceCount) {
        if(owner==null || userId<=0 || taskId==null || hash==null || hash.isBlank()
                || localPeerId==null || localPeerId.length!=20)
            throw new IllegalArgumentException("Invalid task Peer scope");
        if(taskScopes.putIfAbsent(owner,new TaskIdle(userId,taskId,hash,localPeerId,pieceCount))!=null)
            throw new IllegalStateException("Task Peer scope already active");
    }
    List<InetSocketAddress> inboundPeers(long userId,UUID taskId) {
        for(var task:taskScopes.values()) {
            if(task.owner==userId && task.task.equals(taskId)) {
                var result=new ArrayList<InetSocketAddress>();
                for(var endpoint:task.inboundPeers)
                    if(task.idle.keySet().stream().anyMatch(k->k.address().equals(
                            endpoint.getAddress().getHostAddress()) && k.port()==endpoint.getPort()))
                        result.add(endpoint);
                return List.copyOf(result);
            }
        }
        return List.of();
    }
    /**
     * Accept one already-established public-swarm socket. Reject ambiguous
     * InfoHashes belonging to multiple users; never import a connection into
     * the wrong task, and never accept data until a verified BEP3 handshake.
     */
    void adoptInbound(Socket socket) throws IOException {
        boolean adopted=false;
        try {
            if(socket==null || socket.isClosed() || socket.getInetAddress()==null)
                throw new IOException("Invalid inbound Peer");
            var pinned=policy.resolve(socket.getInetAddress().getHostAddress());
            if(!pinned.equals(socket.getInetAddress()))
                throw new IOException("Inbound Peer address changed");
            socket.setSoTimeout(3000);
            var in=new BufferedInputStream(socket.getInputStream(),128*1024);
            var out=new BufferedOutputStream(socket.getOutputStream(),32*1024);
            long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
            var original=codec.decodeHandshake(ByteBuffer.wrap(read(in,socket,
                    PeerWireCodec.HANDSHAKE_LENGTH,deadline))).orElseThrow();
            String infoHash=HexFormat.of().formatHex(original.infoHash());
            TaskIdle task=null;
            for(var candidate:taskScopes.values()) {
                if(!candidate.hash.equalsIgnoreCase(infoHash))continue;
                if(task!=null)throw new IOException("Ambiguous inbound InfoHash");
                task=candidate;
            }
            if(task==null || task.pieceCount<1 ||
                    Arrays.equals(task.localPeerId,new byte[20]) ||
                    Arrays.equals(original.peerId(),task.localPeerId))
                throw new IOException("No uniquely authorized public task for inbound Peer");
            InetSocketAddress endpoint=new InetSocketAddress(
                    socket.getInetAddress(),socket.getPort());
            var key=new Key(socket.getInetAddress().getHostAddress(),socket.getPort(),
                    task.hash,HexFormat.of().formatHex(task.localPeerId));
            write(out,codec.encodeHandshake(new PeerWireCodec.Handshake(
                    new byte[8],original.infoHash(),task.localPeerId)).array(),deadline);
            var link=new Connection(key,socket,in,out);
            link.inbound=true;
            send(link,new PeerWireCodec.Message(2,new byte[0]),deadline);
            for(int i=0;i<24 && !link.unchoked;i++) {
                var message=receive(link,deadline);
                updateAdvertised(link,message,task.pieceCount);
                if(message.id()==1)link.unchoked=true;
            }
            if(!link.unchoked)throw new IOException("Inbound Peer did not unchoke");
            synchronized(task) {
                if(!taskScopes.containsValue(task))
                    throw new IOException("Inbound task was cancelled");
                if(task.idle.size()>=TASK_IDLE_CAP)
                    throw new IOException("Inbound Peer pool is full");
                Connection old=task.idle.putIfAbsent(key,link);
                if(old!=null)throw new IOException("Duplicate inbound Peer connection");
                task.inboundPeers.add(endpoint);
                task.openedSockets.increment();
                adopted=true;
            }
        }catch(RuntimeException failure) {
            throw new IOException("Invalid inbound Peer handshake",failure);
        }finally {
            if(!adopted && socket!=null)close(socket);
        }
    }
    void endTask(Thread owner) {
        if(owner==null)return;
        TaskIdle task=taskScopes.remove(owner);
        if(task==null)return;
        synchronized(task) {
            end(owner); // closes any active round rather than retaining its sockets
            for(Connection link:task.idle.values())close(link.socket);
            task.idle.clear();
            task.inboundPeers.clear();
        }
    }
    void begin(Thread owner) {
        if(owner==null || !roundOwners.add(owner))
            throw new IllegalStateException("Native peer round is already active");
        roundMembers.put(owner,ConcurrentHashMap.newKeySet());
    }
    void register(Thread owner,Thread child) {
        if(owner==null || child==null)throw new IllegalArgumentException("Missing Peer thread");
        if(roundOwners.contains(owner)) {
            roundMembers.get(owner).add(child);
            childParents.put(child,owner);
            if(owner.isInterrupted())child.interrupt();
        }
    }
    void unregister(Thread owner,Thread child) {
        childParents.remove(child,owner);
        // Retain round membership for cancellation of idle cached sockets.
    }
    boolean scoped(Thread current) {
        Thread parent=childParents.get(current);
        return roundOwners.contains(current) ||
                (parent!=null && roundOwners.contains(parent));
    }

    void end(Thread owner) {
        roundOwners.remove(owner);
        TaskIdle task=owner.isInterrupted()?null:taskScopes.get(owner);
        Set<Thread> children=roundMembers.remove(owner);
        if(children!=null)for(Thread child:children) {
            childParents.remove(child,owner);
            retireThread(child,task);
        }
        retireThread(owner,task);
    }

    private void retireThread(Thread worker,TaskIdle task) {
        if(task==null) {closeThread(worker);return;}
        Socket pending=connecting.remove(worker);
        if(pending!=null)close(pending);
        var held=connections.remove(worker);
        if(held==null)return;
        for(Connection link:held.values()) {
            if(link.socket.isClosed() ||
                    System.nanoTime()-link.lastUsed>cacheLifetimeNanos(task,link) ||
                    !link.key.hash.equalsIgnoreCase(task.hash) ||
                    task.idle.size()>=TASK_IDLE_CAP) {
                close(link.socket);continue;
            }
            Connection previous=task.idle.putIfAbsent(link.key,link);
            if(previous!=null)close(link.socket);
        }
    }

    boolean abort(Thread worker) {
        if(worker==null)return false;
        boolean any=closeThread(worker);
        TaskIdle task=taskScopes.get(worker);
        if(task!=null) {
            for(Connection link:task.idle.values()) {close(link.socket);any=true;}
            task.idle.clear();
        }
        Set<Thread> children=roundMembers.get(worker);
        if(children!=null)for(Thread child:List.copyOf(children)) {
            child.interrupt();
            any=closeThread(child)||any;
        }
        return any;
    }

    boolean abortRoundChildren(Thread owner) {
        if(owner==null)return false;
        boolean any=false;
        Set<Thread> children=roundMembers.get(owner);
        if(children!=null)for(Thread child:List.copyOf(children)) {
            child.interrupt();
            any=closeThread(child)||any;
        }
        return any;
    }

    private boolean closeThread(Thread thread) {
        boolean any=false;
        Socket pending=connecting.remove(thread);
        if(pending!=null) {
            close(pending);
            any=true;
        }
        var pool=connections.remove(thread);
        if(pool!=null)for(var entry:pool.values()) {
            close(entry.socket);any=true;
        }
        return any;
    }

    byte[] download(InetSocketAddress peer,TorrentInfo info,byte[] peerId,
                    PieceBlockPlanner planner,int pieceIndex,Duration timeout) throws IOException {
        return download(peer,info,peerId,planner,pieceIndex,timeout,
                new PieceBlockAssembler(planner,pieceIndex));
    }
    byte[] download(InetSocketAddress peer,TorrentInfo info,byte[] peerId,
                    PieceBlockPlanner planner,int pieceIndex,Duration timeout,
                    PieceBlockAssembler assembler) throws IOException {
        if(assembler==null || assembler.pieceIndex()!=pieceIndex ||
                assembler.isComplete())throw new IllegalArgumentException("Invalid retry Piece assembler");
        if(peer==null || info==null || peerId==null || peerId.length!=20 ||
                planner==null || pieceIndex<0 || pieceIndex>=planner.pieceCount() ||
                timeout==null || timeout.isZero() || timeout.isNegative() ||
                timeout.compareTo(Duration.ofSeconds(60))>0 ||
                peer.getPort()<1 || peer.getPort()>65535)
            throw new IllegalArgumentException("Invalid reusable Peer Piece request");
        timeout=NativeNetworkBudget.cap(timeout);
        Thread current=Thread.currentThread();
        if(!scoped(current))throw new IOException("Reusable Peer socket outside active round");
        byte[] hash=HexFormat.of().parseHex(info.infoHash());
        if(hash.length!=20)throw new IllegalArgumentException("Invalid InfoHash");
        var pinned=new InetSocketAddress(policy.resolve(peer.getHostString()),peer.getPort());
        var key=new Key(pinned.getAddress().getHostAddress(),pinned.getPort(),
                info.infoHash(),HexFormat.of().formatHex(peerId));
        long deadline=System.nanoTime()+timeout.toNanos();

        var threadPool=connections.computeIfAbsent(current,t->new ConcurrentHashMap<>());
        Thread parent=childParents.getOrDefault(current,current);
        TaskIdle session=taskScopes.get(parent);
        Connection link=threadPool.get(key);
        if(link==null) {
            if(session!=null && key.hash.equalsIgnoreCase(session.hash)) {
                Connection cached=session.idle.remove(key);
                if(cached!=null) {
                    if(!cached.socket.isClosed() &&
                            System.nanoTime()-cached.lastUsed<=cacheLifetimeNanos(session,cached)) {
                        link=cached;
                        threadPool.put(key,link);
                        if(!cached.inbound)session.crossRoundReuseHits.increment();
                    }else close(cached.socket);
                }
            }
        }
        if(link!=null && (link.socket.isClosed() ||
                System.nanoTime()-link.lastUsed>cacheLifetimeNanos(session,link))) {
            threadPool.remove(key,link);
            close(link.socket);
            link=null;
        }
        try {
            if(link!=null && session!=null)session.reuseHits.increment();
            if(link==null) {
                if(threadPool.size()>=PER_THREAD_CAP) {
                    // Each network thread owns its cache; a bounded cache cannot leak.
                    var stale=threadPool.entrySet().iterator().next();
                    if(threadPool.remove(stale.getKey(),stale.getValue()))
                        close(stale.getValue().socket);
                }
                link=connect(current,key,pinned,hash,peerId,deadline,info.pieceLayout().pieceCount());
                if(!scoped(current) || current.isInterrupted()) {
                    close(link.socket);
                    throw new IOException("Peer round cancelled before socket lease");
                }
                threadPool.put(key,link);
                connecting.remove(current,link.socket);
                if(session!=null)session.openedSockets.increment();
            }
            if(link.advertised!=null && !hasPiece(link.advertised,pieceIndex))
                throw new PieceUnavailableException();
            byte[] piece=receivePiece(link,peer,info,planner,pieceIndex,deadline,assembler);
            link.lastUsed=System.nanoTime();
            return piece;
        }catch(IOException | RuntimeException failure) {
            // This peer can still serve other Pieces; do not destroy an
            // authenticated session because it lacks one requested Piece.
            if(failure instanceof PieceUnavailableException)throw failure;
            if(failure instanceof IOException)ioFailures.incrementAndGet();
            if(link!=null) {
                threadPool.remove(key,link);
                close(link.socket);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    interface BlockWinnerListener {
        void accepted(PeerBlockMailbox.Winner winning) throws IOException;
    }

    /** Experimental multi-Piece read loop. Exactly one actor owns this
     * authenticated TCP stream; the task mailbox multiplexes distinct Piece
     * indexes across Peers. The feature is off by default.
     */
    void runMailbox(InetSocketAddress peer,TorrentInfo info,byte[] peerId,
                    PieceBlockPlanner planner,PeerBlockMailbox mailbox,
                    PeerPieceAvailability availability,
                    java.util.concurrent.ConcurrentHashMap<InetSocketAddress,
                        java.util.concurrent.ConcurrentLinkedQueue<PeerBlockMailbox.Request>> cancels,
                    BlockWinnerListener listener,
                    PieceDownloadCoordinator.WorkerGuard guard,
                    Duration budget) throws IOException {
        if(peer==null || info==null || peerId==null || peerId.length!=20 ||
                planner==null || mailbox==null || availability==null ||
                cancels==null || listener==null || guard==null ||
                budget==null || budget.isZero() || budget.isNegative())
            throw new IllegalArgumentException("Invalid multi-Piece Peer scope");
        Thread current=Thread.currentThread();
        if(!scoped(current))throw new IOException("Multi-Piece Peer outside authorized round");
        var pinned=new InetSocketAddress(policy.resolve(peer.getHostString()),peer.getPort());
        var key=new Key(pinned.getAddress().getHostAddress(),pinned.getPort(),info.infoHash(),
                HexFormat.of().formatHex(peerId));
        byte[] hash=HexFormat.of().parseHex(info.infoHash());
        long deadline=System.nanoTime()+NativeNetworkBudget.cap(budget).toNanos();
        var local=connections.computeIfAbsent(current,t->new ConcurrentHashMap<>());
        Thread parent=childParents.getOrDefault(current,current);
        TaskIdle task=taskScopes.get(parent);
        Connection link=local.get(key);
        if(link==null && task!=null && task.hash.equalsIgnoreCase(info.infoHash())) {
            Connection old=task.idle.remove(key);
            if(old!=null) {
                if(!old.socket.isClosed() &&
                        System.nanoTime()-old.lastUsed<=cacheLifetimeNanos(task,old)) {
                    local.put(key,old);
                    link=old;
                    if(!old.inbound)task.crossRoundReuseHits.increment();
                }else close(old.socket);
            }
        }
        if(link!=null && (link.socket.isClosed() ||
                System.nanoTime()-link.lastUsed>cacheLifetimeNanos(task,link))) {
            local.remove(key,link);
            close(link.socket);
            link=null;
        }
        try {
            if(link==null) {
                if(local.size()>=PER_THREAD_CAP) {
                    var evict=local.entrySet().iterator().next();
                    if(local.remove(evict.getKey(),evict.getValue()))
                        close(evict.getValue().socket);
                }
                guard.beforeNetworkRequest();
                link=connect(current,key,pinned,hash,peerId,deadline,planner.pieceCount());
                if(!scoped(current) || current.isInterrupted())
                    throw new IOException("Multi-Piece round cancelled during handshake");
                local.put(key,link);
                connecting.remove(current,link.socket);
                if(task!=null)task.openedSockets.increment();
            } else if(task!=null)task.reuseHits.increment();
            var cancellation=cancels.computeIfAbsent(peer,
                    ignored->new java.util.concurrent.ConcurrentLinkedQueue<PeerBlockMailbox.Request>());
            var pipeline=new PeerBlockPipelineController();
            var incremental=new PeerIncrementalFrameReader();
            long idleSince=System.nanoTime();
            long nextReassignmentCheck=0;
            PeerPieceAvailability cachedAvailability=availability;
            int cachedAdvertisedRevision=-1;
            int controls=0;
            while(!Thread.currentThread().isInterrupted() &&
                    System.nanoTime()<deadline && mailbox.remaining()>0 &&
                    // After the round deadline the owner stops issuing new
                    // claims; exit as soon as this socket has nothing in
                    // flight instead of spinning until the actor deadline.
                    (!mailbox.closedForClaiming() || mailbox.peerOutstanding(peer)>0)) {
                guard.beforeNetworkRequest();
                var cancel=cancellation.poll();
                while(cancel!=null) {
                    send(link,PeerBlockMailboxFrames.cancel(cancel),deadline);
                    cancel=cancellation.poll();
                }
                // Peer Bitfield is static between HAVE notifications.
                // Do not reconstruct a potentially large BitSet every 2ms.
                if(link.advertised!=null &&
                        cachedAdvertisedRevision!=link.advertisedRevision) {
                    BitSet present=new BitSet(planner.pieceCount());
                    for(int i=0;i<planner.pieceCount();i++)
                        if(hasPiece(link.advertised,i))present.set(i);
                    cachedAvailability=new PeerPieceAvailability(planner.pieceCount(),
                            Map.of(peer,present));
                    cachedAdvertisedRevision=link.advertisedRevision;
                }
                PeerPieceAvailability currentAvailability=cachedAvailability;
                // Refill while the authenticated TCP stream is alive, not
                // only at a Piece boundary. RTT + payload rate set the queue
                // capacity; each write batch remains bounded to 16 Blocks.
                int slots=pipeline.nextBatch(mailbox.peerOutstanding(peer));
                if(slots>0) {
                    long now=System.nanoTime();
                    var claimed=new ArrayList<>(mailbox.claim(
                            peer,currentAvailability,now,slots));
                    if(!mailbox.closedForClaiming() && claimed.size()<slots &&
                            now>=nextReassignmentCheck) {
                        // An overdue scan is O(unfinished Blocks). Avoid
                        // rescanning from each 2ms actor poll at 64+ Peers.
                        nextReassignmentCheck=now+Duration.ofMillis(250).toNanos();
                        var transferred=mailbox.reassignOverdue(peer,
                                currentAvailability,now,slots-claimed.size());
                        for(var transfer:transferred) {
                            cancels.computeIfAbsent(transfer.cancel().peer(),
                                    ignored->new java.util.concurrent.ConcurrentLinkedQueue<>())
                                    .add(transfer.cancel());
                            claimed.add(transfer.request());
                        }
                    }
                    if(!claimed.isEmpty()) {
                        for(var request:claimed) {
                            guard.beforeNetworkRequest();
                            link.out.write(codec.encodeMessage(
                                    PeerBlockMailboxFrames.request(request)).array());
                        }
                        link.out.flush();
                        recordMailboxWindow(current,mailbox.peerOutstanding(peer),
                                pipeline.target(),claimed.size(),false,0);
                        idleSince=System.nanoTime();
                    }
                }
                if(mailbox.peerOutstanding(peer)==0) {
                    // Another Peer may be holding the last Block. Keep this
                    // authenticated actor available for later end-game racing
                    // or for a failed Peer lease; leave early only when this
                    // Peer cannot supply any unfinished Piece.
                    if(System.nanoTime()-idleSince>Duration.ofSeconds(3).toNanos() &&
                            !mailbox.hasPotentialWork(peer,currentAvailability))break;
                    if(link.in.available()>0) {
                        var ctrl=incremental.poll(link.in);
                        if(ctrl!=null) {
                            updateAdvertised(link,ctrl,planner.pieceCount());
                            idleSince=System.nanoTime();
                        }
                    }
                    try {Thread.sleep(15);}
                    catch(InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Multi-Piece actor interrupted",stopped);
                    }
                    continue;
                }
                // Use socket-level timeout blocking read to eliminate Windows
                // 15.6ms timer resolution penalties and busy spin-wait CPU burns.
                // Partial frames are retained across short timeouts.
                PeerWireCodec.Message frame=incremental.readFrame(link.in,link.socket,50);
                if(frame==null) {
                    long now=System.nanoTime();
                    long progress=incremental.hasPartialFrame()?
                            incremental.lastProgressNanos():idleSince;
                    // When blocks are outstanding, stallTimeoutMillis of absolute silence
                    // without any bytes indicates a dead/frozen peer. Fail early to free its blocks.
                    if(now-progress>java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(stallTimeoutMillis))
                        throw new SocketTimeoutException(
                                "Peer stalled with Block requests outstanding");
                    continue;
                }
                idleSince=System.nanoTime();
                updateAdvertised(link,frame,planner.pieceCount());
                if(frame.id()==0)throw new IOException("Peer choked multi-Piece requests");
                if(frame.id()!=7) {
                    if(++controls>128)
                        throw new IOException("Peer sent too many non-Piece messages");
                    continue;
                }
                var result=PeerBlockMailboxFrames.piece(mailbox,peer,frame);
                controls=0;
                if(result.delivery()==PeerBlockMailbox.Delivery.ACCEPTED) {
                    var winner=result.winner();
                    byte[] payload=winner.payload();
                    long rtt=mailbox.lastRttNanos(peer);
                    pipeline.received(payload.length,rtt,System.nanoTime());
                    recordMailboxWindow(current,mailbox.peerOutstanding(peer),
                            pipeline.target(),0,true,rtt);
                    recordReceivedPayload(current,payload.length);
                    listener.accepted(winner);
                    for(var loser:winner.cancel())
                        cancels.computeIfAbsent(loser.peer(),ignored->
                                new java.util.concurrent.ConcurrentLinkedQueue<>()).add(loser);
                }
            }
            // Send any last CANCELs before reuse/retirement. If the end-game
            // raced two sockets, late in-flight data may still be on the wire:
            // retire these sockets rather than contaminate another Piece round.
            var finalCancel=cancellation.poll();
            while(finalCancel!=null) {
                send(link,PeerBlockMailboxFrames.cancel(finalCancel),deadline);
                finalCancel=cancellation.poll();
            }
            boolean outstandingOrContaminated=mailbox.shouldRetire(peer);
            boolean partialFrame=incremental.hasPartialFrame();
            if(outstandingOrContaminated || partialFrame) {
                if(DIAGNOSTICS.isLoggable(java.util.logging.Level.FINE))
                    DIAGNOSTICS.fine("native_socket_retire peer="+peer
                            +" uncertainOrOutstanding="+outstandingOrContaminated
                            +" outstandingBlocks="+mailbox.peerOutstanding(peer)
                            +" partialFrame="+partialFrame
                            +" remainingBlocks="+mailbox.remaining()
                            +" queue="+mailbox.queueStats());
                // Never recycle a socket whose next frame has an unfinished
                // header/body or a cancelled in-flight end-game response.
                local.remove(key,link);
                close(link.socket);
                task.retiredPeerSockets.increment();
            }else link.lastUsed=System.nanoTime();
        }catch(IOException | RuntimeException failure) {
            if(link!=null) {
                local.remove(key,link);
                close(link.socket);
            }
            throw failure;
        }finally {
            mailbox.disconnect(peer);
            clearRequestWindow(current);
        }
    }

    private Connection connect(Thread owner,Key key,InetSocketAddress pinned,byte[] hash,
                               byte[] peerId,long deadline,int pieces) throws IOException {
        Socket socket=new Socket();
        socket.setTcpNoDelay(true);
        try { socket.setReceiveBufferSize(512 * 1024); } catch (Exception ignored) {}
        try { socket.setSendBufferSize(128 * 1024); } catch (Exception ignored) {}
        connecting.put(owner,socket);
        try {
            socket.connect(pinned,Math.min(connectTimeoutMillis,remaining(deadline)));
            connects.incrementAndGet();
            socket.setSoTimeout(remaining(deadline));
            var in=new BufferedInputStream(socket.getInputStream(),128*1024);
            var out=new BufferedOutputStream(socket.getOutputStream(),32*1024);
            write(out,codec.encodeHandshake(
                    new PeerWireCodec.Handshake(new byte[8],hash,peerId)).array(),deadline);
            long handshakeDeadline=Math.min(deadline,
                    System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(handshakeTimeoutMillis));
            var parsed=codec.decodeHandshake(ByteBuffer.wrap(
                    read(in,socket,PeerWireCodec.HANDSHAKE_LENGTH,handshakeDeadline))).orElseThrow();
            if(!Arrays.equals(hash,parsed.infoHash()) ||
                    Arrays.equals(peerId,parsed.peerId()))
                throw new IOException("Reusable Peer handshake identity mismatch");
            var result=new Connection(key,socket,in,out);
            send(result,new PeerWireCodec.Message(2,new byte[0]),deadline);
            long unchokeDeadline=Math.min(deadline,
                    System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(handshakeTimeoutMillis));
            for(int i=0;i<32;i++) {
                var message=receive(result,unchokeDeadline);
                updateAdvertised(result,message,pieces);
                if(message.id()==1) {
                    result.unchoked=true;
                    return result;
                }
            }
            throw new IOException("Reusable Peer did not unchoke");
        }catch(IOException | RuntimeException exception) {
            close(socket);
            connecting.remove(owner,socket);
            if(exception instanceof IllegalArgumentException)
                throw new IOException("Malformed reusable Peer message",exception);
            throw exception;
        }
    }

    private byte[] receivePiece(Connection link,InetSocketAddress remote,
                                TorrentInfo info,PieceBlockPlanner planner,
                                int pieceIndex,long deadline,PieceBlockAssembler assembler)
            throws IOException {
        // Only a bounded per-Peer request window; avoid sending every block
        // of a large Piece before receiving any responses.
        var pipeline=new PeerBlockRequestWindow(assembler.missingBlocks(planner));
        try {
        var initial=pipeline.fill();
        sendPieceRequests(link,pieceIndex,initial,deadline);
        recordRequestWindow(Thread.currentThread(),remote,pipeline,initial.size(),false);
        int silent=0;
        try {
        while(!assembler.isComplete()) {
            if(silent>=64)throw new IOException("Peer stopped answering Piece blocks");
            long nextBlockDeadline=pipeline.nextDeadlineNanos();
            if(System.nanoTime()>=nextBlockDeadline)
                throw new SocketTimeoutException("Pending 16KiB Block exceeded RTT-aware deadline");
            var frame=receive(link,Math.min(deadline,nextBlockDeadline));
            updateAdvertised(link,frame,planner.pieceCount());
            if(frame.id()==0)throw new IOException("Reusable Peer choked");
            if(frame.id()!=7){silent++;continue;}
            ByteBuffer body=ByteBuffer.wrap(frame.payload());
            if(body.remaining()<8)throw new IOException("Truncated reusable Piece response");
            int actual=body.getInt(),offset=body.getInt();
            byte[] bytes=new byte[body.remaining()];
            body.get(bytes);
            pipeline.acknowledge(pieceIndex,actual,offset,bytes.length);
            recordRequestWindow(Thread.currentThread(),remote,pipeline,0,true);
            try {assembler.accept(actual,offset,bytes);}
            catch(IllegalArgumentException e){throw new IOException("Malformed Piece block",e);}
            // Count received, validly requested block payload immediately,
            // independently of the 64-Piece durable commit boundary.
            recordReceivedPayload(Thread.currentThread(),bytes.length);
            var refill=pipeline.fill();
            sendPieceRequests(link,pieceIndex,refill,deadline);
            recordRequestWindow(Thread.currentThread(),remote,pipeline,refill.size(),false);
            silent=0;
        }
        }catch(SocketTimeoutException stalled) {
            try {
                PeerBlockCancellation.cancelOutstanding(link.out,codec,pieceIndex,pipeline);
            }catch(IOException cancelFailure) {stalled.addSuppressed(cancelFailure);}
            throw stalled;
        }
        remaining(deadline);
        try {return assembler.verifiedPiece(info);}
        catch(IllegalArgumentException e) {
            assembler.reset(); // never reuse blocks that failed the whole-Piece hash
            throw new IOException("Reusable Piece SHA-1 mismatch",e);
        }
        }finally {clearRequestWindow(Thread.currentThread());}
    }

    private void sendPieceRequests(Connection link,int pieceIndex,
                                   List<PieceBlockPlanner.Block> blocks,long deadline)
            throws IOException {
        if(blocks.isEmpty())return;
        for(var block:blocks) {
            ByteBuffer payload=ByteBuffer.allocate(12)
                    .putInt(pieceIndex).putInt(block.begin()).putInt(block.length());
            remaining(deadline);
            link.out.write(codec.encodeMessage(
                    new PeerWireCodec.Message(6,payload.array())).array());
        }
        remaining(deadline);
        link.out.flush();
        remaining(deadline);
    }

    private static void updateAdvertised(Connection link,PeerWireCodec.Message frame,int pieces)
            throws IOException {
        if(frame.id()==5) {
            if(link.advertised!=null || frame.payload().length!=((long)pieces+7)/8)
                throw new IOException("Invalid or duplicate reusable Bitfield");
            for(int index=pieces;index<frame.payload().length*8;index++)
                if(hasPiece(frame.payload(),index))
                    throw new IOException("Invalid reusable Bitfield padding");
            link.advertised=frame.payload();
            link.advertisedRevision++;
        }else if(frame.id()==4) {
            int index=ByteBuffer.wrap(frame.payload()).getInt();
            if(index>=pieces)throw new IOException("Invalid reusable HAVE index");
            if(link.advertised!=null) {
                link.advertised[index/8]|=(byte)(0x80>>>(index%8));
                link.advertisedRevision++;
            }
        }
    }

    private static boolean hasPiece(byte[] field,int index) {
        return index/8<field.length &&
                (field[index/8] & (0x80 >>> (index%8)))!=0;
    }
    private void send(Connection link,PeerWireCodec.Message message,long deadline)
            throws IOException {
        write(link.out,codec.encodeMessage(message).array(),deadline);
    }
    private static void write(OutputStream out,byte[] bytes,long deadline)throws IOException {
        remaining(deadline);out.write(bytes);out.flush();remaining(deadline);
    }
    private PeerWireCodec.Message receive(Connection link,long deadline)throws IOException {
        byte[] header=read(link.in,link.socket,4,deadline);
        int size=ByteBuffer.wrap(header).getInt();
        if(size<0 || size>64*1024)throw new IOException("Peer frame exceeds cap");
        byte[] body=read(link.in,link.socket,size,deadline);
        try {
            return codec.decodeMessage(ByteBuffer.wrap(
                    ByteBuffer.allocate(size+4).putInt(size).put(body).array()),64*1024)
                    .orElseThrow();
        }catch(IllegalArgumentException e){throw new IOException("Invalid Peer frame",e);}
    }
    private static byte[] read(InputStream in,Socket socket,int size,long deadline)
            throws IOException {
        byte[] out=new byte[size];
        int off=0;
        while(off<size) {
            socket.setSoTimeout(remaining(deadline));
            int n=in.read(out,off,size-off);
            if(n<0)throw new IOException("Peer disconnected");
            if(n>0)off+=n;
        }
        remaining(deadline);
        return out;
    }
    private static int remaining(long deadline)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Peer worker interrupted");
        long nanos=deadline-System.nanoTime();
        if(nanos<=0)throw new SocketTimeoutException("Peer I/O deadline exceeded");
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,(nanos+999_999)/1_000_000));
    }
    private void close(Socket socket) {
        if(socket==null || socket.isClosed())return;
        try{socket.close();}catch(IOException ignored){}
        finally {closes.incrementAndGet();}
    }
}
