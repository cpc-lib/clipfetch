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

class PeerResumableBlockTransferTest {
    @TempDir Path root;
    static final PeerWireCodec WIRE=new PeerWireCodec();
    static final int BLOCK=PieceBlockPlanner.BLOCK_BYTES;
    static final byte[] DATA=new byte[16*BLOCK];
    static {
        for(int i=0;i<DATA.length;i++) DATA[i]=(byte)(i*37+i/311);
    }
    private static PeerWireCodec.Message readFrame(InputStream in) throws Exception {
        byte[] length=in.readNBytes(4);
        if(length.length==0)return null;
        assertEquals(4,length.length);
        int size=ByteBuffer.wrap(length).getInt();
        assertTrue(size>=0 && size<=65536);
        byte[] body=in.readNBytes(size);
        assertEquals(size,body.length);
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(size+4).putInt(size).put(body).array())).orElseThrow();
    }
    private static void serve(ServerSocket server,boolean first,AtomicInteger delivered)
            throws Exception {
        try(Socket socket=server.accept()) {
            socket.setSoTimeout(3000);
            var in=socket.getInputStream();
            var out=socket.getOutputStream();
            byte[] original=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
            assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,original.length);
            var handshake=WIRE.decodeHandshake(ByteBuffer.wrap(original)).orElseThrow();
            byte[] remote=new byte[20];
            Arrays.fill(remote,(byte)(first?11:12));
            out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                    new byte[8],handshake.infoHash(),remote)).array());
            assertEquals(2,readFrame(in).id());
            out.write(WIRE.encodeMessage(new PeerWireCodec.Message(
                    5,new byte[]{(byte)0x80})).array());
            out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
            out.flush();
            int requested=0;
            while(requested<(first?16:8)) {
                var message=readFrame(in);
                assertNotNull(message);
                if(message.id()!=6)continue;
                var request=ByteBuffer.wrap(message.payload());
                int piece=request.getInt(),begin=request.getInt(),length=request.getInt();
                assertEquals(0,piece);
                assertEquals(BLOCK,length);
                if(first)assertTrue(begin>=0 && begin<DATA.length);
                else assertTrue(begin>=8*BLOCK,
                        "Second Peer must not be asked to resend verified-in-memory blocks");
                requested++;
                if(first && begin>=8*BLOCK)continue; // simulate lost connection after 8 blocks
                byte[] payload=ByteBuffer.allocate(8+length)
                        .putInt(piece).putInt(begin)
                        .put(DATA,begin,length).array();
                out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
                out.flush();
                delivered.incrementAndGet();
            }
        }
    }
    @Test void failedPeerRetainsBlocksWithinSamePieceAndOnlyFetchesMissingFromAnotherPeer()
            throws Exception {
        byte[] expected=MessageDigest.getInstance("SHA-1").digest(DATA);
        String hash=HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest("fixture-info".getBytes()));
        var info=new TorrentInfo(hash,"resume-fixture",
                List.of(new PieceLayout.FileEntry(0,"resume.bin",0,DATA.length)),
                DATA.length,DATA.length,expected);
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),root,28,UUID.randomUUID());
        var firstCount=new AtomicInteger();
        var secondCount=new AtomicInteger();
        try(ServerSocket first=new ServerSocket(0);ServerSocket second=new ServerSocket(0);
            var session=new PeerFixture(first,second,firstCount,secondCount)) {
            var peerSession=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var coordinator=new PieceDownloadCoordinator(
                    checkpoint,peerSession::downloadPiece,
                    List.of(new InetSocketAddress("127.0.0.1",first.getLocalPort()),
                            new InetSocketAddress("127.0.0.1",second.getLocalPort())),
                    new byte[20],2,Duration.ofSeconds(3),2,peerSession);
            var result=coordinator.runRound(1);
            assertEquals(1,result.committedPieces());
            assertEquals(2,result.peerAttempts());
            assertEquals(8,firstCount.get());
            assertEquals(8,secondCount.get());
            assertEquals(DATA.length,checkpoint.verifiedSelectedBytes());
            session.check();
        }
    }
    static class PeerFixture implements AutoCloseable {
        final ExecutorService executor=Executors.newFixedThreadPool(2);
        final Future<?> first,second;
        PeerFixture(ServerSocket a,ServerSocket b,AtomicInteger firstCount,AtomicInteger secondCount) {
            first=executor.submit(()->{serveChecked(a,true,firstCount);});
            second=executor.submit(()->{serveChecked(b,false,secondCount);});
        }
        static void serveChecked(ServerSocket socket,boolean first,AtomicInteger count) {
            try {serve(socket,first,count);}
            catch(Exception failure){throw new RuntimeException(failure);}
        }
        void check() throws Exception {
            first.get(5,TimeUnit.SECONDS);
            second.get(5,TimeUnit.SECONDS);
        }
        @Override public void close() {
            executor.shutdownNow();
        }
    }
}
