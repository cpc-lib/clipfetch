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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.BitSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/**
 * P3 prototype: download and verify ONE wanted v1 Piece through BEP3.
 * Only one outstanding request at a time; no disk writes, resume, or progress accounting.
 */
public final class PeerPieceDownloadSession implements PieceDownloadCoordinator.ChildThreads {
    @Override public void register(Thread parent,Thread child) {registerChild(parent,child);}
    @Override public void unregister(Thread parent,Thread child) {unregisterChild(parent,child);}
    @Override public void beginRound(Thread owner) {
        if(reuseEnabled.getAsBoolean())reusePool.begin(owner);
    }
    @Override public void endRound(Thread owner) {reusePool.end(owner);}
    private final TrackerAddressPolicy addressPolicy;
    private final PeerWireCodec wire = new PeerWireCodec();
    private final BooleanSupplier reuseEnabled;
    private final PeerRoundConnectionPool reusePool;
    private final int peerMaxInFlightBlocks;
    private final TaskPeerActorPool actors=new TaskPeerActorPool();
    ThreadPoolExecutor borrowActors(Thread owner,int slots) {return actors.borrow(owner,slots);}
    boolean actorScope(Thread owner){return actors.active(owner);}
    /** Continuous-job-only scope; never used by anonymous/single rounds. */
    public void beginTask(long owner,java.util.UUID task,String infoHash) {
        beginTask(owner,task,infoHash,new byte[20],0);
    }
    public void beginTask(long owner,java.util.UUID task,String infoHash,
                          byte[] peerId,int pieceCount) {
        Thread thread=Thread.currentThread();
        reusePool.beginTask(thread,owner,task,infoHash,peerId,pieceCount);
        try {actors.begin(thread,owner,task,infoHash);}
        catch(RuntimeException failed) {reusePool.endTask(thread);throw failed;}
    }
    public void endTask() {
        Thread thread=Thread.currentThread();
        // Interrupt actors and close their sockets before retiring the scope.
        actors.close(thread);
        reusePool.endTask(thread);
    }
    public NativePeerRequestTelemetry.Snapshot requestTelemetry(long owner,java.util.UUID task) {
        return reusePool.requestTelemetry(owner,task);
    }
    long peerRttP95Nanos(InetSocketAddress peer) {
        return reusePool.peerRttP95Nanos(Thread.currentThread(),peer);
    }
    public java.util.List<InetSocketAddress> inboundPeers(long owner,java.util.UUID task) {
        return reusePool.inboundPeers(owner,task);
    }
    public void acceptInbound(Socket socket) throws IOException {
        reusePool.adoptInbound(socket);
    }
    void runMailbox(InetSocketAddress peer,
                    cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo info,
                    byte[] peerId,PieceBlockPlanner planner,PeerBlockMailbox mailbox,
                    PeerPieceAvailability availability,
                    java.util.concurrent.ConcurrentHashMap<InetSocketAddress,
                        java.util.concurrent.ConcurrentLinkedQueue<PeerBlockMailbox.Request>> cancellations,
                    PeerRoundConnectionPool.BlockWinnerListener winner,
                    PieceDownloadCoordinator.WorkerGuard guard,Duration budget) throws IOException {
        if(!reuseEnabled.getAsBoolean() || !reusePool.scoped(Thread.currentThread()))
            throw new IOException("Multi-Piece mailbox requires authorized TCP reuse");
        reusePool.runMailbox(peer,info,peerId,planner,mailbox,availability,
                cancellations,winner,guard,budget);
    }
    public long receivedPayloadBytes(long owner,java.util.UUID task) {
        return reusePool.receivedPayloadBytes(owner,task);
    }
    public record TaskConnectionStats(long openedSockets,long reuseHits,
                                      long crossRoundReuseHits,int idleSockets,
                                      long retiredPeerSockets,int targetPeerActors,
                                      int activePeerActors,long actorPeerReassignments) {}
    public TaskConnectionStats taskConnectionStats(long owner,java.util.UUID task) {
        var counters=reusePool.taskConnectionStats(owner,task);
        return new TaskConnectionStats(counters.openedSockets(),counters.reuseHits(),
                counters.crossRoundReuseHits(),counters.idleSockets(),
                counters.retiredPeerSockets(),counters.targetPeerActors(),
                counters.activePeerActors(),counters.actorPeerReassignments());
    }
    /** Publish Actor supervisor metrics for the owner's active task scope. */
    public void setActorMetrics(Thread owner,int targetActors,int activeActors,long reassignments) {
        reusePool.setActorMetrics(owner,targetActors,activeActors,reassignments);
    }
    List<InetSocketAddress> idlePeers(Thread owner) {
        return reusePool.idlePeers(owner);
    }
    // Per-session active TCP sockets, closed on explicit worker cancellation.
    private final ConcurrentHashMap<Thread,Socket> activeSockets=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread,java.util.Set<Thread>> ownedWorkers=new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong directConnects=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong directClosures=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong directIoFailures=new java.util.concurrent.atomic.AtomicLong();
    public record DirectSocketCounters(long connected,long closed,long ioFailures,int registered) {}
    /** Direct-Peer counters only. Reusable pool lifecycle is excluded. */
    public DirectSocketCounters directSocketCounters() {
        return new DirectSocketCounters(directConnects.get(),directClosures.get(),
                directIoFailures.get(),activeSockets.size());
    }

    /** Number of currently registered direct Peer TCP sockets. Excludes reusable pool sockets. */
    public int activeDirectSocketCount() {
        return activeSockets.size();
    }

    /** Reusable pool socket registrations, including connections being established. */
    public int registeredReusableSocketCount() {
        return reusePool.registeredSocketCount();
    }

    public record ReusableSocketCounters(long connected,long closed,long ioFailures,int registered) {}
    public ReusableSocketCounters reusableSocketCounters() {
        var stats=reusePool.counters();
        return new ReusableSocketCounters(stats.connected(),stats.closed(),
                stats.ioFailures(),stats.registered());
    }

    /** Direct and reusable registrations. Snapshot can vary during concurrent I/O. */
    public int registeredSocketCount() {
        return activeDirectSocketCount()+registeredReusableSocketCount();
    }

    /** Register parallel network workers under the authenticated task's parent Worker. */
    public void registerChild(Thread owner,Thread child) {
        if(owner==null || child==null)throw new IllegalArgumentException("Missing worker identity");
        ownedWorkers.computeIfAbsent(owner,k->ConcurrentHashMap.newKeySet()).add(child);
        reusePool.register(owner,child);
        if(owner.isInterrupted())child.interrupt();
    }
    public void unregisterChild(Thread owner,Thread child) {
        reusePool.unregister(owner,child);
        var children=ownedWorkers.get(owner);
        if(children!=null) {
            children.remove(child);
            if(children.isEmpty())ownedWorkers.remove(owner,children);
        }
    }

    /** Closing a platform Socket wakes a blocked read faster than interrupt alone. */
    public boolean abortForThread(Thread worker) {
        if(worker==null)return false;
        worker.interrupt(); // cancellation must be visible before closed sockets wake reads
        boolean closed=abortSingle(worker);
        var children=ownedWorkers.get(worker);
        if(children!=null)for(Thread child:List.copyOf(children)) {
            child.interrupt();
            closed=abortSingle(child) || closed;
        }
        return reusePool.abort(worker) || closed;
    }
    /** Abort only child network workers under the owner without interrupting the owner itself. */
    public boolean abortChildren(Thread owner) {
        if(owner==null)return false;
        boolean closed=false;
        var children=ownedWorkers.get(owner);
        if(children!=null)for(Thread child:List.copyOf(children)) {
            child.interrupt();
            closed=abortSingle(child) || closed;
        }
        return reusePool.abortRoundChildren(owner) || closed;
    }
    private boolean abortSingle(Thread worker) {
        Socket socket=activeSockets.get(worker);
        if(socket==null)return false;
        try {socket.close();return true;}
        catch(IOException e) {return false;}
    }

    public PeerPieceDownloadSession(TrackerAddressPolicy addressPolicy) {
        this(addressPolicy,()->false);
    }
    public PeerPieceDownloadSession(TrackerAddressPolicy addressPolicy,
                                    BooleanSupplier reuseEnabled) {
        this(addressPolicy, reuseEnabled, 2000, 8000, 1500,
                PeerBlockMailbox.DEFAULT_MAX_PEER_IN_FLIGHT);
    }
    public PeerPieceDownloadSession(TrackerAddressPolicy addressPolicy,
                                    BooleanSupplier reuseEnabled,
                                    int handshakeTimeoutMillis,
                                    int stallTimeoutMillis,
                                    int connectTimeoutMillis) {
        this(addressPolicy,reuseEnabled,handshakeTimeoutMillis,stallTimeoutMillis,
                connectTimeoutMillis,PeerBlockMailbox.DEFAULT_MAX_PEER_IN_FLIGHT);
    }
    public PeerPieceDownloadSession(TrackerAddressPolicy addressPolicy,
                                    BooleanSupplier reuseEnabled,
                                    int handshakeTimeoutMillis,
                                    int stallTimeoutMillis,
                                    int connectTimeoutMillis,
                                    int peerMaxInFlightBlocks) {
        if(addressPolicy==null || reuseEnabled==null)
            throw new IllegalArgumentException("Missing Peer transport config");
        if(peerMaxInFlightBlocks<PeerBlockRequestWindow.INITIAL ||
                peerMaxInFlightBlocks>PeerBlockRequestWindow.MAX_ALLOWED)
            throw new IllegalArgumentException("Invalid Peer transport window");
        this.addressPolicy=addressPolicy;
        this.reuseEnabled=reuseEnabled;
        this.peerMaxInFlightBlocks=peerMaxInFlightBlocks;
        this.reusePool=new PeerRoundConnectionPool(addressPolicy,
                handshakeTimeoutMillis, stallTimeoutMillis, connectTimeoutMillis,
                peerMaxInFlightBlocks);
    }

    int peerMaxInFlightBlocks() {return peerMaxInFlightBlocks;}

    /**
     * Best-effort BEP3 snapshot. Only a full, padding-valid BITFIELD is
     * authoritative for absence; HAVE messages alone do not imply absence.
     *
     * This opens a short-lived independent socket; a future session pool can
     * reuse established connections. Caller bounds the number of probes.
     */
    public Optional<BitSet> probeBitfield(InetSocketAddress peer,TorrentInfo info,
                                           byte[] localPeerId,Duration timeout) throws IOException {
        if(peer==null || info==null || localPeerId==null || localPeerId.length!=20 ||
                timeout==null || timeout.isNegative() || timeout.isZero() ||
                timeout.compareTo(Duration.ofSeconds(5))>0 ||
                peer.getPort()<1 || peer.getPort()>65535)
            throw new IllegalArgumentException("Invalid Piece availability probe");
        int pieces=info.pieceLayout().pieceCount();
        long bytesNeeded=((long)pieces+7L)/8L;
        if(pieces<=0 || bytesNeeded>64*1024-1)
            throw new IllegalArgumentException("Torrent bitfield exceeds bounded probe frame");
        byte[] hash=HexFormat.of().parseHex(info.infoHash());
        if(hash.length!=20)throw new IllegalArgumentException("Invalid InfoHash");
        timeout=NativeNetworkBudget.cap(timeout);
        long deadline=System.nanoTime()+timeout.toNanos();
        var pinned=new InetSocketAddress(addressPolicy.resolve(peer.getHostString()),peer.getPort());
        try(Socket socket=new Socket()) {
            Thread owner=Thread.currentThread();
            activeSockets.put(owner,socket);
            try {
                socket.connect(pinned,Math.min(2500,remainingMillis(deadline)));
                socket.setSoTimeout(remainingMillis(deadline));
                var in=socket.getInputStream();
                var out=socket.getOutputStream();
                sendRaw(out,wire.encodeHandshake(new PeerWireCodec.Handshake(
                        new byte[8],hash,localPeerId)).array(),deadline);
                PeerWireCodec.Handshake remote;
                try {
                    remote=wire.decodeHandshake(ByteBuffer.wrap(
                            readExact(in,socket,PeerWireCodec.HANDSHAKE_LENGTH,deadline))).orElseThrow();
                }catch(IllegalArgumentException e) {
                    throw new IOException("Malformed availability Peer handshake",e);
                }
                if(!Arrays.equals(hash,remote.infoHash()) ||
                        Arrays.equals(localPeerId,remote.peerId()))
                    throw new IOException("Availability Peer identity mismatch");
                sendMessage(out,new PeerWireCodec.Message(2,new byte[0]),deadline);
                BitSet known=null;
                for(int messages=0;messages<12;messages++) {
                    PeerWireCodec.Message frame;
                    try {frame=receive(in,socket,deadline);}
                    catch(SocketTimeoutException e) {break;} // Peer did not advertise a complete field
                    if(frame.id()==5) {
                        if(known!=null)throw new IOException("Duplicate Bitfield");
                        if(frame.payload().length!=bytesNeeded)
                            throw new IOException("Invalid Bitfield length");
                        known=decodeBitfield(frame.payload(),pieces);
                    }else if(frame.id()==4 && known!=null) {
                        int index=ByteBuffer.wrap(frame.payload()).getInt();
                        if(index>=pieces)throw new IOException("HAVE index exceeds Piece count");
                        known.set(index);
                    }else if(frame.id()==1) {
                        // A full BITFIELD is not guaranteed by an unchoke/HAVE alone.
                        break;
                    }
                }
                return Optional.ofNullable(known);
            }finally {
                activeSockets.remove(owner,socket);
            }
        }
    }

    private static BitSet decodeBitfield(byte[] bytes,int pieces) throws IOException {
        BitSet result=new BitSet(pieces);
        for(int i=0;i<bytes.length;i++) {
            int value=Byte.toUnsignedInt(bytes[i]);
            for(int bit=0;bit<8;bit++) {
                int index=i*8+bit;
                if((value & (0x80>>>bit))==0)continue;
                if(index>=pieces)throw new IOException("Nonzero Bitfield padding bits");
                result.set(index);
            }
        }
        return result;
    }

    public byte[] downloadPiece(InetSocketAddress peer, TorrentInfo info, byte[] localPeerId,
                                PieceBlockPlanner planner, int pieceIndex,
                                Duration timeout) throws IOException {
        return downloadResumablePiece(peer,info,localPeerId,planner,pieceIndex,timeout,
                new PieceBlockAssembler(planner,pieceIndex));
    }
    /**
     * Only a single Piece worker calls this, retaining its in-memory blocks
     * across failed Peers. No partial blocks are shared between workers/tasks
     * or persisted until the full Piece passes the torrent hash.
     */
    byte[] downloadResumablePiece(InetSocketAddress peer,TorrentInfo info,byte[] localPeerId,
                                  PieceBlockPlanner planner,int pieceIndex,Duration timeout,
                                  PieceBlockAssembler assembler) throws IOException {
        if(assembler==null || assembler.pieceIndex()!=pieceIndex || assembler.isComplete())
            throw new IllegalArgumentException("Invalid retry Piece assembler");
        if (reuseEnabled.getAsBoolean() && reusePool.scoped(Thread.currentThread()))
            return reusePool.download(peer,info,localPeerId,planner,pieceIndex,timeout,assembler);
        if (peer == null || peer.getPort() < 1 || peer.getPort() > 65535
                || info == null || planner == null || localPeerId == null
                || localPeerId.length != 20 || timeout == null || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Invalid Piece request");
        byte[] infoHash;
        try { infoHash = HexFormat.of().parseHex(info.infoHash()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid v1 InfoHash", e); }
        if (infoHash.length != 20) throw new IllegalArgumentException("InfoHash must be 20 bytes");
        timeout=NativeNetworkBudget.cap(timeout);
        long deadline = System.nanoTime() + timeout.toNanos();
        var pinned = new InetSocketAddress(addressPolicy.resolve(peer.getHostString()),peer.getPort());

        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            try { socket.setReceiveBufferSize(512 * 1024); } catch (Exception ignored) {}
            try { socket.setSendBufferSize(128 * 1024); } catch (Exception ignored) {}
            Thread worker=Thread.currentThread();
            activeSockets.put(worker,socket);
            try {
                if(worker.isInterrupted())throw new IOException("Piece request already cancelled");
            socket.connect(pinned,Math.min(2500,remainingMillis(deadline)));
            directConnects.incrementAndGet();
            socket.setSoTimeout(remainingMillis(deadline));
            InputStream in = new BufferedInputStream(socket.getInputStream(), 128 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
            sendRaw(out,wire.encodeHandshake(new PeerWireCodec.Handshake(
                    new byte[8],infoHash,localPeerId)).array(),deadline);
            PeerWireCodec.Handshake remote;
            try {
                long handshakeDeadline=Math.min(deadline,
                        System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
                remote = wire.decodeHandshake(ByteBuffer.wrap(
                        readExact(in,socket,PeerWireCodec.HANDSHAKE_LENGTH,handshakeDeadline))).orElseThrow();
            } catch (IllegalArgumentException e) { throw new IOException("Malformed Peer handshake",e); }
            if (!Arrays.equals(infoHash,remote.infoHash())
                    || Arrays.equals(localPeerId,remote.peerId()))
                throw new IOException("Peer identity does not match torrent");

            sendMessage(out,new PeerWireCodec.Message(2,new byte[0]),deadline); // interested
            boolean unchoked = false;
            long unchokeDeadline=Math.min(deadline,
                    System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            for (int messages=0;!unchoked && messages<32;messages++) {
                PeerWireCodec.Message response = receive(in,socket,unchokeDeadline);
                if (response.id() == 5 && !hasPiece(response.payload(),pieceIndex))
                    throw new IOException("Peer bitfield indicates Piece is unavailable");
                if (response.id() == 1) unchoked = true;
            }
            if (!unchoked) throw new IOException("Peer did not unchoke");

            // Keep only the configured bounded number of unacknowledged
            // 16 KiB requests; refill as blocks arrive.
            var pipeline=new PeerBlockRequestWindow(assembler.missingBlocks(planner),
                    System::nanoTime,peerMaxInFlightBlocks);
            var firstBlocks=pipeline.fill();
            sendPieceRequests(out,pieceIndex,firstBlocks,deadline);
            reusePool.recordRequestWindow(Thread.currentThread(),peer,pipeline,firstBlocks.size(),false);
            int silentMessages = 0;
            try {
            while (!assembler.isComplete()) {
                if (silentMessages >= 64) throw new IOException("Peer stopped answering Piece blocks");
                long due=pipeline.nextDeadlineNanos();
                if(System.nanoTime()>=due)
                    throw new SocketTimeoutException("Pending Piece Block exceeded RTT deadline");
                PeerWireCodec.Message response=receive(in,socket,Math.min(deadline,due));
                if (response.id() == 0) throw new IOException("Peer choked during Piece request");
                if (response.id() != 7) { silentMessages++; continue; }
                ByteBuffer piece = ByteBuffer.wrap(response.payload());
                if(piece.remaining()<8)throw new IOException("Truncated Peer Piece response");
                int actualPiece = piece.getInt(), offset = piece.getInt();
                byte[] bytes = new byte[piece.remaining()];
                piece.get(bytes);
                pipeline.acknowledge(pieceIndex,actualPiece,offset,bytes.length);
                reusePool.recordRequestWindow(Thread.currentThread(),peer,pipeline,0,true);
                try { assembler.accept(actualPiece, offset, bytes); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid Piece block", e); }
                reusePool.recordReceivedPayload(Thread.currentThread(),bytes.length);
                var additional=pipeline.fill();
                sendPieceRequests(out,pieceIndex,additional,deadline);
                reusePool.recordRequestWindow(Thread.currentThread(),peer,pipeline,additional.size(),false);
                silentMessages = 0;
            }
            }catch(SocketTimeoutException stalled) {
                try {PeerBlockCancellation.cancelOutstanding(out,wire,pieceIndex,pipeline);}
                catch(IOException cancelFailure){stalled.addSuppressed(cancelFailure);}
                throw stalled;
            }
            remainingMillis(deadline);
            try { return assembler.verifiedPiece(info); }
            catch (IllegalArgumentException e) {
                assembler.reset(); // never retain blocks after failed SHA-1
                throw new IOException("Piece SHA-1 validation failed",e);
            }
            } catch(IOException failure) {
                directIoFailures.incrementAndGet();
                throw failure;
            } finally {
                reusePool.clearRequestWindow(Thread.currentThread());
                activeSockets.remove(worker,socket);
                directClosures.incrementAndGet();
            }
        }
    }

    private void sendPieceRequests(OutputStream out,int pieceIndex,
                                   List<PieceBlockPlanner.Block> blocks,long deadline)
            throws IOException {
        if(blocks.isEmpty())return;
        for(var block:blocks) {
            ByteBuffer request=ByteBuffer.allocate(12)
                    .putInt(pieceIndex).putInt(block.begin()).putInt(block.length());
            remainingMillis(deadline);
            out.write(wire.encodeMessage(new PeerWireCodec.Message(6,request.array())).array());
        }
        remainingMillis(deadline);
        out.flush();
        remainingMillis(deadline);
    }

    private boolean hasPiece(byte[] bitfield,int index) {
        int offset=index/8;
        return offset < bitfield.length && (bitfield[offset] & (0x80 >>> (index%8))) != 0;
    }

    private void sendMessage(OutputStream out,PeerWireCodec.Message frame,long deadline) throws IOException {
        sendRaw(out,wire.encodeMessage(frame).array(),deadline);
    }
    private static void sendRaw(OutputStream out,byte[] bytes,long deadline) throws IOException {
        remainingMillis(deadline);
        out.write(bytes);
        out.flush();
        remainingMillis(deadline);
    }
    private PeerWireCodec.Message receive(InputStream in,Socket socket,long deadline) throws IOException {
        byte[] prefix=readExact(in,socket,4,deadline);
        int length=ByteBuffer.wrap(prefix).getInt();
        if (length < 0 || length > 64*1024) throw new IOException("Oversized Peer frame");
        byte[] data=readExact(in,socket,length,deadline);
        ByteBuffer message=ByteBuffer.allocate(4+length).putInt(length).put(data);
        message.flip();
        try { return wire.decodeMessage(message,64*1024).orElseThrow(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid Peer frame",e); }
    }
    private static byte[] readExact(InputStream in,Socket socket,int n,long deadline) throws IOException {
        byte[] data = new byte[n];
        int offset=0;
        while (offset<n) {
            socket.setSoTimeout(remainingMillis(deadline));
            int got=in.read(data,offset,n-offset);
            if (got<0) throw new IOException("Peer disconnected during Piece transfer");
            if (got>0) offset+=got;
        }
        remainingMillis(deadline);
        return data;
    }
    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Piece transfer interrupted");
        long nanos=deadline-System.nanoTime();
        if (nanos<=0) throw new SocketTimeoutException("Piece transfer deadline exceeded");
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,(nanos+999_999L)/1_000_000L));
    }
}
