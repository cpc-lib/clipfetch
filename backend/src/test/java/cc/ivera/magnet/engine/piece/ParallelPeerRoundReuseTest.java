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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParallelPeerRoundReuseTest {
    @TempDir Path directory;
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static final byte[] DATA="abcdefghijklmnop".getBytes(StandardCharsets.US_ASCII);

    @Test void twoParallelWorkersDownloadFourPiecesWithTwoHandshakes() throws Exception {
        byte[] hashes=new byte[80];
        for(int piece=0;piece<4;piece++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,piece*4,piece*4+4)),0,hashes,piece*20,20);
        var info=new TorrentInfo("1".repeat(40),"round",
                List.of(new PieceLayout.FileEntry(0,"round.bin",0,DATA.length)),16,4,hashes);
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),directory,22,UUID.randomUUID());
        var concurrent=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var connections=new AtomicInteger();
        var requests=new AtomicInteger();
        var finished=new CountDownLatch(2);
        var serverErrors=new ConcurrentLinkedQueue<Throwable>();
        try(ServerSocket listener=new ServerSocket(0);
            ServerSocket secondListener=new ServerSocket(0)) {
            listener.setSoTimeout(4000);
            secondListener.setSoTimeout(4000);
            Thread acceptor=new Thread(()->{
                try {
                    for(int i=0;i<2;i++) {
                        // Adaptive admission reserves one lane per eligible Peer.
                        Socket peer=(i==0?listener:secondListener).accept();
                        connections.incrementAndGet();
                        Thread handler=new Thread(()->{
                            try(peer) {
                                peer.setSoTimeout(3800);
                                serve(peer,concurrent,release,requests);
                            }catch(Throwable t){serverErrors.add(t);}
                            finally {finished.countDown();}
                        },"native-peer-reuse-mock");
                        handler.setDaemon(true);
                        handler.start();
                    }
                }catch(Throwable t){serverErrors.add(t);release.countDown();}
            });
            acceptor.setDaemon(true);
            acceptor.start();
            Thread releaser=new Thread(()->{
                try {concurrent.await(3,TimeUnit.SECONDS);}
                catch(InterruptedException ignored){}
                finally {release.countDown();}
            });
            releaser.start();
            try {
                var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
                var coordinator=new PieceDownloadCoordinator(checkpoint,session::downloadPiece,
                        List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),
                                new InetSocketAddress("127.0.0.1",secondListener.getLocalPort())),
                        new byte[20],1,Duration.ofSeconds(4),2,session);
                var result=coordinator.runRound(4);
                assertEquals(4,result.committedPieces());
                assertEquals(DATA.length,result.verifiedSelectedBytes());
                assertEquals(4,requests.get());
                assertEquals(2,connections.get(),
                        "Each parallel worker should retain its own Peer TCP session");
                assertTrue(finished.await(2,TimeUnit.SECONDS),
                        "Cached sockets must close when the Piece round completes");
                assertTrue(serverErrors.isEmpty(),serverErrors.toString());
            }finally {
                release.countDown();
                listener.close();
                acceptor.join(1000);
                releaser.join(1000);
            }
        }
    }

    private static void serve(Socket peer,CountDownLatch concurrent,CountDownLatch release,
                              AtomicInteger requests)throws Exception {
        var in=peer.getInputStream();
        var out=peer.getOutputStream();
        var raw=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
        if(raw.length!=PeerWireCodec.HANDSHAKE_LENGTH)throw new EOFException();
        var handshake=WIRE.decodeHandshake(ByteBuffer.wrap(raw)).orElseThrow();
        byte[] remoteId=new byte[20];Arrays.fill(remoteId,(byte)7);
        out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                new byte[8],handshake.infoHash(),remoteId)).array());
        out.flush();
        if(read(in).id()!=2)throw new IOException("Client failed to send Interested");
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(5,new byte[]{(byte)0xF0})).array());
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
        out.flush();
        boolean first=true;
        PeerWireCodec.Message frame;
        while((frame=read(in))!=null) {
            if(frame.id()!=6)continue;
            ByteBuffer request=ByteBuffer.wrap(frame.payload());
            int piece=request.getInt(),offset=request.getInt(),bytes=request.getInt();
            if(first) {
                first=false;
                concurrent.countDown();
                if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Not concurrent");
            }
            requests.incrementAndGet();
            byte[] response=ByteBuffer.allocate(8+bytes).putInt(piece).putInt(offset)
                    .put(Arrays.copyOfRange(DATA,piece*4+offset,piece*4+offset+bytes)).array();
            out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,response)).array());
            out.flush();
        }
    }

    private static PeerWireCodec.Message read(InputStream input)throws Exception {
        byte[] head=input.readNBytes(4);
        if(head.length==0)return null;
        if(head.length!=4)throw new EOFException();
        int n=ByteBuffer.wrap(head).getInt();
        if(n<0||n>64*1024)throw new IOException("Invalid frame");
        byte[] body=input.readNBytes(n);
        if(body.length!=n)throw new EOFException();
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(n+4).putInt(n).put(body).array())).orElseThrow();
    }
}
