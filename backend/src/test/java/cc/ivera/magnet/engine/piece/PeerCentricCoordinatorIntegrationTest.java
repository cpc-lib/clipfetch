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

class PeerCentricCoordinatorIntegrationTest {
    @TempDir Path root;
    private static final PeerWireCodec CODEC=new PeerWireCodec();
    private static final byte[] DATA=new byte[]{11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};

    @Test void activePeerActorsOnlyRequestPiecesAdvertisedByTheirPeer() throws Exception {
        byte[] hashes=new byte[80];
        for(int piece=0;piece<4;piece++) {
            byte[] sha=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,piece*4,piece*4+4));
            System.arraycopy(sha,0,hashes,piece*20,20);
        }
        var torrent=new TorrentInfo("b".repeat(40),"peer-centred",
                List.of(new PieceLayout.FileEntry(0,"data.bin",0,DATA.length)),
                DATA.length,4,hashes);
        var verified=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,27,UUID.randomUUID());
        var actorSession=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0);
            var fixtureA=new MockPeer(a,(byte)0xa0,Set.of(0,2));
            var fixtureB=new MockPeer(b,(byte)0x50,Set.of(1,3))) {
            var pa=new InetSocketAddress("127.0.0.1",a.getLocalPort());
            var pb=new InetSocketAddress("127.0.0.1",b.getLocalPort());
            var bitsA=new BitSet();bitsA.set(0);bitsA.set(2);
            var bitsB=new BitSet();bitsB.set(1);bitsB.set(3);
            var availability=new PeerPieceAvailability(4,Map.of(pa,bitsA,pb,bitsB));
            byte[] myPeerId=new byte[20];
            var task=UUID.randomUUID();
            actorSession.beginTask(27,task,torrent.infoHash());
            try {
                var coordinator=new PieceDownloadCoordinator(verified,
                        actorSession::downloadPiece,List.of(pa,pb),myPeerId,
                        2,Duration.ofSeconds(8),2,actorSession,availability);
                var result=coordinator.runRound(4);
                assertEquals(4,result.committedPieces());
                assertEquals(16,verified.verifiedSelectedBytes());
                assertEquals(0,result.failedPieces().size());
                assertEquals(2,fixtureA.requested.get());
                assertEquals(2,fixtureB.requested.get());
                assertEquals(2,actorSession.taskConnectionStats(27,task).openedSockets());
            }finally {actorSession.endTask();}
            fixtureA.awaitSuccess();
            fixtureB.awaitSuccess();
        }
    }

    static final class MockPeer implements AutoCloseable {
        final AtomicInteger requested=new AtomicInteger();
        final ExecutorService executor=Executors.newSingleThreadExecutor();
        final Future<?> task;
        MockPeer(ServerSocket listener,byte bitfield,Set<Integer> allowed) {
            task=executor.submit(()->{
                try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(10000);
                    var in=socket.getInputStream();
                    var out=socket.getOutputStream();
                    var received=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    if(received.length!=PeerWireCodec.HANDSHAKE_LENGTH)
                        throw new IOException("Truncated handshake");
                    var handshake=CODEC.decodeHandshake(ByteBuffer.wrap(received)).orElseThrow();
                    byte[] peerId=new byte[20];
                    Arrays.fill(peerId,(byte)(bitfield&0xff));
                    out.write(CODEC.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],handshake.infoHash(),peerId)).array());
                    if(read(in).id()!=2)throw new IOException("Expected interested");
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{bitfield})).array());
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    out.flush();
                    PeerWireCodec.Message request;
                    while((request=read(in))!=null) {
                        if(request.id()!=6)continue;
                        var fields=ByteBuffer.wrap(request.payload());
                        int piece=fields.getInt(),begin=fields.getInt(),bytes=fields.getInt();
                        if(!allowed.contains(piece))
                            throw new IOException("Peer-centric Actor requested an absent Piece: "+piece);
                        if(begin!=0 || bytes!=4)
                            throw new IOException("Invalid Block request");
                        requested.incrementAndGet();
                        var body=ByteBuffer.allocate(8+4).putInt(piece).putInt(begin)
                                .put(DATA,piece*4,4).array();
                        out.write(CODEC.encodeMessage(new PeerWireCodec.Message(7,body)).array());
                        out.flush();
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
        }
        static PeerWireCodec.Message read(InputStream in) throws Exception {
            var size=in.readNBytes(4);
            if(size.length==0)return null;
            if(size.length!=4)throw new EOFException();
            int length=ByteBuffer.wrap(size).getInt();
            if(length<0 || length>65536)throw new IOException("Invalid length");
            var body=in.readNBytes(length);
            if(body.length!=length)throw new EOFException();
            return CODEC.decodeMessage(ByteBuffer.wrap(ByteBuffer.allocate(length+4)
                    .putInt(length).put(body).array())).orElseThrow();
        }
        void awaitSuccess() throws Exception {task.get(8,TimeUnit.SECONDS);}
        @Override public void close(){executor.shutdownNow();}
    }
}
