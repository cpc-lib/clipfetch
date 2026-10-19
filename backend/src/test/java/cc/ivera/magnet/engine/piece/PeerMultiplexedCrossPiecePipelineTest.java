package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** A single real socket requests Piece N+1 before Piece N can be completed. */
class PeerMultiplexedCrossPiecePipelineTest {
    @TempDir Path temp;
    static final PeerWireCodec CODEC=new PeerWireCodec();
    static final int BLOCK=PieceBlockPlanner.BLOCK_BYTES;
    static final int PIECE=BLOCK*16;

    @Test void actualTcpRequestBatchIncludesNextPieceBeforeAnyBlockResponse()
            throws Exception {
        byte[] content=new byte[2*PIECE];
        for(int i=0;i<content.length;i++)content[i]=(byte)(i*31+17);
        byte[] hashes=new byte[40];
        for(int i=0;i<2;i++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(content,i*PIECE,(i+1)*PIECE)),
                    0,hashes,i*20,20);
        var info=new TorrentInfo("d".repeat(40),"cross-piece",
                List.of(new PieceLayout.FileEntry(0,"cross-piece.bin",0,content.length)),
                content.length,PIECE,hashes);
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var task=UUID.randomUUID();
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var firstBatchPieceZero=new AtomicInteger();
        var firstBatchPieceOne=new AtomicInteger();
        var totalRequests=new AtomicInteger();
        var peerPool=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0)) {
            Future<?> server=peerPool.submit(()->{
                try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(7000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    byte[] raw=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    var handshake=CODEC.decodeHandshake(ByteBuffer.wrap(raw)).orElseThrow();
                    byte[] peerId=new byte[20];Arrays.fill(peerId,(byte)2);
                    out.write(CODEC.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],handshake.infoHash(),peerId)).array());
                    if(read(in).id()!=2)throw new IOException("Missing Interested");
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{(byte)0xc0})).array());
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    out.flush();
                    // The client initially fills 16 requests. Do not answer
                    // any until observing all 16, proving cross-Piece lookahead.
                    var first=new ArrayList<PeerWireCodec.Message>();
                    for(int i=0;i<16;i++) {
                        var req=read(in);
                        if(req==null || req.id()!=6)
                            throw new IOException("Expected 16 early BEP3 REQUESTs");
                        int piece=ByteBuffer.wrap(req.payload()).getInt();
                        if(piece==0)firstBatchPieceZero.incrementAndGet();
                        else if(piece==1)firstBatchPieceOne.incrementAndGet();
                        else throw new IOException("Wrong Piece request");
                        first.add(req);
                    }
                    for(var req:first)respond(req,out,content,totalRequests);
                    out.flush();
                    while(totalRequests.get()<32) {
                        var req=read(in);
                        if(req==null)throw new EOFException("Incomplete Piece pipeline");
                        if(req.id()!=6)continue;
                        respond(req,out,content,totalRequests);
                        out.flush();
                    }
                    while(read(in)!=null){} // wait for owner task cleanup
                }catch(Exception error){throw new RuntimeException(error);}
            });
            var peer=new InetSocketAddress("127.0.0.1",listener.getLocalPort());
            var bits=new BitSet();bits.set(0);bits.set(1);
            session.beginTask(82,task,info.infoHash());
            try {
                Thread owner=Thread.currentThread();
                session.beginRound(owner);
                var result=PeerMultiplexedRound.fetch(owner,session,info,planner,
                        List.of(0,1),List.of(peer),new byte[20],
                        new PeerPieceAvailability(2,Map.of(peer,bits)),
                        1,Duration.ofSeconds(9),()->{});
                assertEquals(2,result.verified().size());
                for(int i=0;i<2;i++)
                    assertArrayEquals(Arrays.copyOfRange(content,i*PIECE,(i+1)*PIECE),
                            result.verified().get(i));
            }finally {session.endRound(Thread.currentThread());session.endTask();}
            server.get(3,TimeUnit.SECONDS);
            assertEquals(12,firstBatchPieceZero.get());
            assertEquals(4,firstBatchPieceOne.get(),
                    "A single TCP Peer must request Piece 1 before finishing Piece 0");
            assertEquals(32,totalRequests.get());
        }finally {peerPool.shutdownNow();}
    }

    private static void respond(PeerWireCodec.Message request,OutputStream out,
                                byte[] source,AtomicInteger total)throws Exception {
        var fields=ByteBuffer.wrap(request.payload());
        int piece=fields.getInt(),begin=fields.getInt(),size=fields.getInt();
        if(piece<0 || piece>1 || begin<0 || begin+size>PIECE || size!=BLOCK)
            throw new IOException("Invalid Block request");
        byte[] payload=ByteBuffer.allocate(8+size).putInt(piece).putInt(begin)
                .put(source,piece*PIECE+begin,size).array();
        out.write(CODEC.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
        total.incrementAndGet();
    }
    private static PeerWireCodec.Message read(InputStream in)throws Exception {
        var raw=in.readNBytes(4);
        if(raw.length==0)return null;
        if(raw.length<4)throw new EOFException();
        int n=ByteBuffer.wrap(raw).getInt();
        if(n<0 || n>65536)throw new IOException("Invalid BEP3 length");
        var data=in.readNBytes(n);
        if(data.length!=n)throw new EOFException();
        return CODEC.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(4+n).putInt(n).put(data).array())).orElseThrow();
    }
}
