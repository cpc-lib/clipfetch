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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** A real TCP block stream proves the owner can commit while slow actors run. */
class PeerMultiplexedStreamingCommitTest {
    @TempDir Path temp;
    static final byte[] DATA={11,12,13,14,21,22,23,24};
    static final PeerWireCodec WIRE=new PeerWireCodec();

    @Test void ownerPublishesFastVerifiedPieceWhileAnotherTcpActorIsStillWaiting()
            throws Exception {
        byte[] hashes=new byte[40];
        for(int i=0;i<2;i++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,i*4,i*4+4)),0,hashes,i*20,20);
        var info=new TorrentInfo("c".repeat(40),"streamed",
                List.of(new PieceLayout.FileEntry(0,"streamed.bin",0,8)),8,4,hashes);
        var task=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),temp,70,task);
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var allowSlowResponse=new CountDownLatch(1);
        var slowResponded=new AtomicBoolean();
        var publishedFastFirst=new AtomicBoolean();
        var commits=new AtomicInteger();
        try(ServerSocket fastSocket=new ServerSocket(0);
            ServerSocket slowSocket=new ServerSocket(0);
            var fast=new Fixture(fastSocket,0,null,null);
            var slow=new Fixture(slowSocket,1,allowSlowResponse,slowResponded)) {
            var fastAddress=new InetSocketAddress("127.0.0.1",fastSocket.getLocalPort());
            var slowAddress=new InetSocketAddress("127.0.0.1",slowSocket.getLocalPort());
            var bitsFast=new BitSet();bitsFast.set(0);
            var bitsSlow=new BitSet();bitsSlow.set(1);
            session.beginTask(70,task,info.infoHash());
            try {
                var coordinator=new PieceDownloadCoordinator(checkpoint,session::downloadPiece,
                        List.of(fastAddress,slowAddress),new byte[20],
                        2,Duration.ofSeconds(6),2,session,
                        new PeerPieceAvailability(2,Map.of(
                                fastAddress,bitsFast,slowAddress,bitsSlow)));
                coordinator.enableMultiplexedMailbox();
                coordinator.configureGroupCommit(1);
                var owner=Thread.currentThread();
                var result=coordinator.runRound(2,(piece,wire,total)->{
                    assertSame(owner,Thread.currentThread(),
                            "Network Actor must never perform durable checkpoints");
                    commits.incrementAndGet();
                    if(total==4 && !slowResponded.get())
                        publishedFastFirst.set(true);
                    if(total>=4)allowSlowResponse.countDown();
                },()->{});
                assertEquals(2,result.committedPieces());
                assertEquals(8,checkpoint.verifiedSelectedBytes());
                assertEquals(2,commits.get());
                assertTrue(publishedFastFirst.get(),
                        "First Piece must be durably credited before slow TCP response");
            }finally {allowSlowResponse.countDown();session.endTask();}
            fast.awaitSuccess();slow.awaitSuccess();
        }
    }

    static final class Fixture implements AutoCloseable {
        final ExecutorService pool=Executors.newSingleThreadExecutor();
        final Future<?> work;
        Fixture(ServerSocket listener,int allowedPiece,CountDownLatch gate,
                AtomicBoolean responded) {
            work=pool.submit(()->{
                try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(6500);
                    var in=socket.getInputStream();var out=socket.getOutputStream();
                    var input=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    var handshake=WIRE.decodeHandshake(ByteBuffer.wrap(input)).orElseThrow();
                    byte[] id=new byte[20];Arrays.fill(id,(byte)(allowedPiece+1));
                    out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],handshake.infoHash(),id)).array());
                    if(read(in).id()!=2)throw new IOException("Expected interested");
                    out.write(WIRE.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{(byte)(allowedPiece==0?0x80:0x40)})).array());
                    out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    out.flush();
                    PeerWireCodec.Message frame;
                    while((frame=read(in))!=null) {
                        if(frame.id()!=6)continue;
                        var fields=ByteBuffer.wrap(frame.payload());
                        int p=fields.getInt(),begin=fields.getInt(),length=fields.getInt();
                        if(p!=allowedPiece || begin!=0 || length!=4)
                            throw new IOException("Wrong Piece routed over TCP");
                        if(gate!=null && !gate.await(5,TimeUnit.SECONDS))
                            throw new IOException("Owner did not publish first Piece");
                        byte[] payload=ByteBuffer.allocate(12).putInt(p).putInt(0)
                                .put(DATA,p*4,4).array();
                        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
                        out.flush();
                        if(responded!=null)responded.set(true);
                    }
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
        }
        static PeerWireCodec.Message read(InputStream in)throws Exception {
            var len=in.readNBytes(4);
            if(len.length==0)return null;
            if(len.length!=4)throw new EOFException();
            int n=ByteBuffer.wrap(len).getInt();
            if(n<0 || n>65536)throw new IOException("Invalid BEP3 frame");
            var data=in.readNBytes(n);
            if(data.length!=n)throw new EOFException();
            return WIRE.decodeMessage(ByteBuffer.wrap(ByteBuffer.allocate(n+4)
                    .putInt(n).put(data).array())).orElseThrow();
        }
        void awaitSuccess() throws Exception {work.get(3,TimeUnit.SECONDS);}
        @Override public void close(){pool.shutdownNow();}
    }
}
