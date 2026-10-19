package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real TCP: slow Peer holds sixteen of twenty-four Blocks without responding;
 * healthy Peer first gets eight, then must receive timed-out assignments while
 * the torrent is not in end-game (twenty-four Blocks remain).
 */
class PeerMultiplexedOverdueTransferIntegrationTest {
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static final int BLOCK=PieceBlockPlanner.BLOCK_BYTES;

    @Test void healthyPeerTakesOverOverdueBlocksBeforeSlowSocketTimesOut()
            throws Exception {
        byte[] content=new byte[24*BLOCK];
        for(int i=0;i<content.length;i++)content[i]=(byte)(i*19+7);
        var info=new TorrentInfo("e".repeat(40),"slow-reassignment",
                List.of(new PieceLayout.FileEntry(0,"ubuntu-fixture.bin",0,content.length)),
                content.length,content.length,
                MessageDigest.getInstance("SHA-1").digest(content));
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var primaryRequests=new CountDownLatch(1);
        var lateReplyInjected=new CountDownLatch(1);
        var transfersObserved=new AtomicInteger();
        var cancelsObserved=new AtomicInteger();
        var replies=new AtomicInteger();
        var servers=Executors.newFixedThreadPool(2);
        try(ServerSocket listenerA=new ServerSocket(0);ServerSocket listenerB=new ServerSocket(0)) {
            var slow=servers.submit(()->{
                try(Socket socket=listenerA.accept()) {
                    socket.setSoTimeout(25000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    handshake(in,out,(byte)1);
                    for(int n=0;n<16;n++) {
                        var message=read(in);
                        if(message==null || message.id()!=6)
                            throw new IOException("Slow Peer did not receive first 16 requests");
                    }
                    primaryRequests.countDown();
                    PeerWireCodec.Message message;
                    while((message=read(in))!=null) {
                        if(message.id()==8) {
                            cancelsObserved.incrementAndGet();
                            if(lateReplyInjected.getCount()!=0) {
                                // A real cancelled TCP request may already
                                // have been queued by the remote disk. Reply
                                // despite CANCEL and verify client ignores it.
                                var cancelled=ByteBuffer.wrap(message.payload());
                                int piece=cancelled.getInt();
                                int begin=cancelled.getInt();
                                int len=cancelled.getInt();
                                byte[] body=ByteBuffer.allocate(8+len)
                                        .putInt(piece).putInt(begin)
                                        .put(content,begin,len).array();
                                out.write(WIRE.encodeMessage(
                                        new PeerWireCodec.Message(7,body)).array());
                                out.flush();
                                lateReplyInjected.countDown();
                            }
                        }
                    }
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
            var fast=servers.submit(()->{
                try(Socket socket=listenerB.accept()) {
                    socket.setSoTimeout(25000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    if(!primaryRequests.await(4,TimeUnit.SECONDS))
                        throw new IOException("Slow Peer did not claim Block leases");
                    handshake(in,out,(byte)2);
                    var queue=new ArrayList<PeerWireCodec.Message>();
                    for(int n=0;n<9;n++) {
                        var request=read(in);
                        if(request==null || request.id()!=6)
                            throw new IOException("Fast Peer missing takeover REQUEST");
                        queue.add(request);
                    }
                    for(int n=0;n<8;n++) {
                        int begin=ByteBuffer.wrap(queue.get(n).payload()).getInt(4);
                        if(begin<16*BLOCK)
                            throw new IOException("Original slow Peer lease was not exclusive");
                    }
                    int transferredBegin=ByteBuffer.wrap(queue.get(8).payload()).getInt(4);
                    if(transferredBegin>=16*BLOCK)
                        throw new IOException("No pre-endgame overdue transfer observed");
                    transfersObserved.incrementAndGet();
                    if(!lateReplyInjected.await(8,TimeUnit.SECONDS))
                        throw new IOException("Old Peer did not get CANCEL before takeover");
                    for(var request:queue)reply(request,content,out,replies);
                    out.flush();
                    while(replies.get()<24) {
                        var request=read(in);
                        if(request==null)throw new EOFException("Missing reassigned Blocks");
                        if(request.id()!=6)continue;
                        reply(request,content,out,replies);
                        out.flush();
                    }
                    // Wait until Owner closes the task's authenticated socket.
                    while(read(in)!=null){}
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
            var a=new InetSocketAddress("127.0.0.1",listenerA.getLocalPort());
            var b=new InetSocketAddress("127.0.0.1",listenerB.getLocalPort());
            var bits=new BitSet();bits.set(0);
            session.beginTask(84,UUID.randomUUID(),info.infoHash());
            long started=System.nanoTime();
            PeerMultiplexedRound.Download outcome;
            try {
                var owner=Thread.currentThread();
                session.beginRound(owner);
                outcome=PeerMultiplexedRound.fetch(owner,session,info,planner,
                        List.of(0),List.of(a,b),new byte[20],
                        new PeerPieceAvailability(1,Map.of(a,bits,b,bits)),
                        2,Duration.ofSeconds(20),()->{});
            }finally {session.endRound(Thread.currentThread());session.endTask();}
            fast.get(25,TimeUnit.SECONDS);
            slow.get(25,TimeUnit.SECONDS);
            var connectionStats=session.reusableSocketCounters();
            assertEquals(2,connectionStats.connected(),
                    "Fault-injection Peers must establish two TCP sockets");
            assertEquals(2,connectionStats.closed(),
                    "All TCP sockets must close after this completed task");
            assertEquals(0,connectionStats.registered(),
                    "No socket with a cancelled Block may survive task completion");
            assertArrayEquals(content,outcome.verified().get(0));
            assertEquals(1,outcome.verified().size());
            assertEquals(0,outcome.failedPeers());
            assertTrue(outcome.transferredBlocks()>=1);
            assertTrue(outcome.lateBlocksDropped()>=1,
                    "A late response from revoked Peer must never be admitted");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<20000,
                    "Healthy takeover should finish before the round deadline");
            assertTrue(transfersObserved.get()>0);
            assertEquals(24,replies.get());
            assertTrue(cancelsObserved.get()>=1,"Timed-out leases should generate CANCEL");
        }finally {servers.shutdownNow();}
    }

    @Test void peerEofAfterUnchokeReleasesOutstandingBlocksToHealthyTcpPeer()
            throws Exception {
        byte[] content=new byte[24*BLOCK];
        for(int i=0;i<content.length;i++)content[i]=(byte)(i*29+11);
        var info=new TorrentInfo("b".repeat(40),"eof-reclaim",
                List.of(new PieceLayout.FileEntry(0,"eof-fixture.bin",0,content.length)),
                content.length,content.length,
                MessageDigest.getInstance("SHA-1").digest(content));
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var eofLeases=new CountDownLatch(1);
        var healthyReplies=new AtomicInteger();
        var servers=Executors.newFixedThreadPool(2);
        try(ServerSocket eofListener=new ServerSocket(0);
            ServerSocket healthyListener=new ServerSocket(0)) {
            var failed=servers.submit(()->{
                try(Socket socket=eofListener.accept()) {
                    socket.setSoTimeout(10000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    handshake(in,out,(byte)3);
                    for(int i=0;i<16;i++) {
                        var request=read(in);
                        if(request==null || request.id()!=6)
                            throw new IOException("EOF Peer did not receive its 16 Block leases");
                    }
                    eofLeases.countDown();
                    // Simulates public Peer: UNCHOKE, accept REQUESTs, then EOF.
                }catch(Exception error){throw new RuntimeException(error);}
            });
            var healthy=servers.submit(()->{
                try(Socket socket=healthyListener.accept()) {
                    socket.setSoTimeout(10000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    if(!eofLeases.await(4,TimeUnit.SECONDS))
                        throw new IOException("EOF Peer never received the first leases");
                    handshake(in,out,(byte)4);
                    PeerWireCodec.Message request;
                    while((request=read(in))!=null) {
                        if(request.id()!=6)continue;
                        reply(request,content,out,healthyReplies);
                        out.flush();
                    }
                }catch(Exception error){throw new RuntimeException(error);}
            });
            var a=new InetSocketAddress("127.0.0.1",eofListener.getLocalPort());
            var b=new InetSocketAddress("127.0.0.1",healthyListener.getLocalPort());
            var bits=new BitSet();bits.set(0);
            session.beginTask(85,UUID.randomUUID(),info.infoHash());
            PeerMultiplexedRound.Download result;
            try {
                var owner=Thread.currentThread();
                session.beginRound(owner);
                try {
                    result=PeerMultiplexedRound.fetch(owner,session,info,planner,
                            List.of(0),List.of(a,b),new byte[20],
                            new PeerPieceAvailability(1,Map.of(a,bits,b,bits)),
                            2,Duration.ofSeconds(9),()->{});
                }finally {session.endRound(owner);}
            }finally {session.endTask();}
            failed.get(4,TimeUnit.SECONDS);
            healthy.get(4,TimeUnit.SECONDS);
            assertArrayEquals(content,result.verified().get(0),
                    "A Peer EOF must release all unfinished leases to the healthy Peer");
            assertEquals(1,result.verified().size());
            assertTrue(healthyReplies.get()>=24,
                    "The healthy Peer must serve the entire verified Piece");
            var sockets=session.reusableSocketCounters();
            assertEquals(2,sockets.connected());
            assertEquals(2,sockets.closed());
            assertEquals(0,sockets.registered());
        }finally {servers.shutdownNow();}
    }

    private static void handshake(InputStream in,OutputStream out,byte id)
            throws Exception {
        var offered=WIRE.decodeHandshake(ByteBuffer.wrap(
                in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH))).orElseThrow();
        byte[] peer=new byte[20];Arrays.fill(peer,id);
        out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                new byte[8],offered.infoHash(),peer)).array());
        if(read(in).id()!=2)throw new IOException("Interested missing");
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(
                5,new byte[]{(byte)0x80})).array());
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
        out.flush();
    }
    private static void reply(PeerWireCodec.Message request,byte[] content,
                              OutputStream out,AtomicInteger total)throws Exception {
        var bb=ByteBuffer.wrap(request.payload());
        int piece=bb.getInt(),begin=bb.getInt(),length=bb.getInt();
        if(piece!=0 || begin<0 || begin+length>content.length || length!=BLOCK)
            throw new IOException("Invalid requested Block");
        byte[] payload=ByteBuffer.allocate(8+length)
                .putInt(piece).putInt(begin).put(content,begin,length).array();
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
        total.incrementAndGet();
    }
    private static PeerWireCodec.Message read(InputStream input)throws IOException {
        byte[] prefix=input.readNBytes(4);
        if(prefix.length==0)return null;
        if(prefix.length!=4)throw new EOFException();
        int length=ByteBuffer.wrap(prefix).getInt();
        if(length<0 || length>65536)throw new IOException("Peer frame exceeds cap");
        byte[] body=input.readNBytes(length);
        if(body.length!=length)throw new EOFException();
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(length+4).putInt(length).put(body).array())).orElseThrow();
    }
}
