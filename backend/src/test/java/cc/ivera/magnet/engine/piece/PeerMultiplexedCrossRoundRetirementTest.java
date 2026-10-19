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

/** Real TCP two-round task: an overdue donor must reconnect, a clean actor must reuse. */
class PeerMultiplexedCrossRoundRetirementTest {
    static final PeerWireCodec WIRE=new PeerWireCodec();
    static final int BLOCK=PieceBlockPlanner.BLOCK_BYTES;
    static final int PIECE=24*BLOCK;

    @Test void contaminatedDonorReconnectsWhileCleanPeerReusesAuthenticatedSocket() throws Exception {
        byte[] content=new byte[2*PIECE];
        for(int i=0;i<content.length;i++)content[i]=(byte)(i*17+3);
        byte[] hashes=new byte[40];
        for(int i=0;i<2;i++)System.arraycopy(
                MessageDigest.getInstance("SHA-1").digest(
                        Arrays.copyOfRange(content,i*PIECE,(i+1)*PIECE)),
                0,hashes,i*20,20);
        var info=new TorrentInfo("f".repeat(40),"two-round",
                List.of(new PieceLayout.FileEntry(0,"fixture.bin",0,content.length)),
                content.length,PIECE,hashes);
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var slowConnections=new AtomicInteger();
        var fastConnections=new AtomicInteger();
        var cancels=new AtomicInteger();
        var late=new AtomicInteger();
        var firstLeases=new CountDownLatch(1);
        var lateResponseFlushed=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try(ServerSocket slowListener=new ServerSocket(0);
            ServerSocket fastListener=new ServerSocket(0)) {
            slowListener.setSoTimeout(25000);
            fastListener.setSoTimeout(25000);
            var slowFuture=workers.submit(()->{
                try {
                    for(int round=0;round<2;round++) {
                        try(Socket socket=slowListener.accept()) {
                            slowConnections.incrementAndGet();
                            socket.setSoTimeout(25000);
                            var in=socket.getInputStream();var out=socket.getOutputStream();
                            handshake(in,out,(byte)1,2);
                            if(round==0) {
                                var initial=new ArrayList<PeerWireCodec.Message>();
                                for(int i=0;i<16;i++) {
                                    var frame=read(in);
                                    if(frame==null || frame.id()!=6)throw new EOFException("Missing slow leases");
                                    initial.add(frame);
                                }
                                firstLeases.countDown();
                                PeerWireCodec.Message msg;
                                while((msg=read(in))!=null) {
                                    if(msg.id()==8) {
                                        cancels.incrementAndGet();
                                        if(late.getAndIncrement()==0) {
                                            // The donor releases its remaining valid leases quickly,
                                            // but also sends the revoked block as a stale TCP reply.
                                            // This avoids dragging the healthy receiver into end-game
                                            // duplicates solely because the fixture withholds all data.
                                            for(var request:initial)reply(request,content,out);
                                            out.flush();
                                            lateResponseFlushed.countDown();
                                            break;
                                        }
                                    }
                                }
                            }
                            // Round 1: slow reconnects (socket was retired in round 0)
                            // but closes immediately so the actor falls back to fast.
                        }
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
            var fastFuture=workers.submit(()->{
                try(Socket socket=fastListener.accept()) {
                    fastConnections.incrementAndGet();
                    socket.setSoTimeout(25000);
                    var in=socket.getInputStream();var out=socket.getOutputStream();
                    if(!firstLeases.await(4,TimeUnit.SECONDS))
                        throw new IOException("Slow actor never claimed first leases");
                    handshake(in,out,(byte)2,2);
                    var first=new ArrayList<PeerWireCodec.Message>();
                    for(int i=0;i<9;i++) {
                        var frame=read(in);
                        if(frame==null || frame.id()!=6)throw new EOFException("Missing transfer request");
                        first.add(frame);
                    }
                    for(int i=0;i<8;i++)
                        if(ByteBuffer.wrap(first.get(i).payload()).getInt(4)<16*BLOCK)
                            throw new IOException("Duplicate unexpired primary");
                    if(ByteBuffer.wrap(first.get(8).payload()).getInt(4)>=16*BLOCK)
                        throw new IOException("No pre-endgame takeover");
                    if(!lateResponseFlushed.await(8,TimeUnit.SECONDS))
                        throw new IOException("Donor did not send a revoked Block after CANCEL");
                    for(var frame:first)reply(frame,content,out);
                    out.flush();
                    // Remain on the SAME authenticated socket throughout round boundary.
                    // Handle any extra first-round requests and the second round alike.
                    PeerWireCodec.Message msg;
                    while((msg=read(in))!=null) {
                        if(msg.id()==6){reply(msg,content,out);out.flush();}
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
            var slow=new InetSocketAddress("127.0.0.1",slowListener.getLocalPort());
            var fast=new InetSocketAddress("127.0.0.1",fastListener.getLocalPort());
            var bits=new BitSet();bits.set(0);bits.set(1);
            var available=new PeerPieceAvailability(2,Map.of(slow,bits,fast,bits));
            var task=UUID.randomUUID();
            session.beginTask(901,task,info.infoHash());
            try {
                var owner=Thread.currentThread();
                for(int round=0;round<2;round++) {
                    session.beginRound(owner);
                    try {
                        var result=PeerMultiplexedRound.fetch(owner,session,info,planner,
                                List.of(round),List.of(slow,fast),new byte[20],
                                round==0?available:new PeerPieceAvailability(2,Map.of(
                                        slow,round1SlowBits(),fast,bits)),
                                2,Duration.ofSeconds(20),()->{});
                        assertNotNull(result.verified().get(round),
                                "round="+round+" result="+result+" stats="+session.taskConnectionStats(901,task)
                                        +" slowAccepted="+slowConnections.get()+" fastAccepted="+fastConnections.get());
                        assertArrayEquals(Arrays.copyOfRange(content,round*PIECE,(round+1)*PIECE),
                                result.verified().get(round));
                        assertEquals(1,result.verified().size());
                        if(round==0) {
                            assertTrue(result.transferredBlocks()>0);
                            assertTrue(result.lateBlocksDropped()>0,
                                    "CANCEL response must be consumed as stale: "+result
                                            +" stats="+session.taskConnectionStats(901,task));
                        }
                    }finally {session.endRound(owner);}
                    if(round==0)
                        assertEquals(1,session.taskConnectionStats(901,task).idleSockets(),
                                "Donor should retire but healthy TCP must stay idle; stats="
                                        +session.taskConnectionStats(901,task)
                                        +" slowAccepted="+slowConnections.get()
                                        +" fastAccepted="+fastConnections.get());
                }
                var stats=session.taskConnectionStats(901,task);
                assertEquals(3,stats.openedSockets(),"Slow must reconnect; fast must not");
                assertEquals(1,stats.crossRoundReuseHits(),"stats="+stats+" slowConnections="+slowConnections.get()+" fastConnections="+fastConnections.get());
                assertEquals(2,slowConnections.get());
                assertEquals(1,fastConnections.get());
                assertTrue(cancels.get()>0);
            }finally {session.endTask();}
            slowFuture.get(25,TimeUnit.SECONDS);
            fastFuture.get(25,TimeUnit.SECONDS);
        }finally {workers.shutdownNow();}
    }

    static BitSet round1SlowBits(){var b=new BitSet();b.set(1);return b;}

    static void handshake(InputStream in,OutputStream out,byte id,int pieces)throws Exception {
        var original=WIRE.decodeHandshake(ByteBuffer.wrap(
                in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH))).orElseThrow();
        byte[] peer=new byte[20];Arrays.fill(peer,id);
        out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                new byte[8],original.infoHash(),peer)).array());
        if(read(in).id()!=2)throw new IOException("Missing interested");
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(5,new byte[]{(byte)(pieces==0?0:0xc0)})).array());
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
        out.flush();
    }
    static void reply(PeerWireCodec.Message req,byte[] data,OutputStream out)throws Exception {
        var b=ByteBuffer.wrap(req.payload());
        int p=b.getInt(),start=b.getInt(),len=b.getInt();
        if(p<0 || p>1 || start<0 || len!=BLOCK || start+len>PIECE)
            throw new IOException("Invalid Block");
        var payload=ByteBuffer.allocate(8+len).putInt(p).putInt(start)
                .put(data,p*PIECE+start,len).array();
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
    }
    static PeerWireCodec.Message read(InputStream in)throws IOException {
        var prefix=in.readNBytes(4);
        if(prefix.length==0)return null;
        if(prefix.length!=4)throw new EOFException();
        int len=ByteBuffer.wrap(prefix).getInt();
        if(len<0 || len>65536)throw new IOException("Invalid frame length");
        var body=in.readNBytes(len);
        if(body.length!=len)throw new EOFException();
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(4+len).putInt(len).put(body).array())).orElseThrow();
    }
}
