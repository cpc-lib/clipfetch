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

class PeerMultiplexedEndgameIntegrationTest {
    @TempDir Path root;
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static PeerWireCodec.Message read(InputStream input) throws Exception {
        var prefix=input.readNBytes(4);
        if(prefix.length==0)return null;
        if(prefix.length!=4)throw new EOFException();
        int length=ByteBuffer.wrap(prefix).getInt();
        if(length<0 || length>65536)throw new IOException("Oversized Peer frame");
        var body=input.readNBytes(length);
        if(body.length!=length)throw new EOFException();
        return WIRE.decodeMessage(ByteBuffer.wrap(ByteBuffer.allocate(length+4)
                .putInt(length).put(body).array())).orElseThrow();
    }
    @Test void fastEndgameBackupWinsAndBothPossiblyStaleSocketsAreRetired() throws Exception {
        var info=new TorrentInfo("d".repeat(40),"endgame",
                List.of(new PieceLayout.FileEntry(0,"endgame.bin",0,4)),
                4,4,MessageDigest.getInstance("SHA-1").digest(new byte[]{1,2,3,4}));
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var primarySeen=new CountDownLatch(1);
        var backupRequests=new AtomicInteger();
        try(ServerSocket listenerA=new ServerSocket(0);ServerSocket listenerB=new ServerSocket(0);
            var fixtureA=new FakePeer(listenerA,primarySeen,null,true,backupRequests);
            var fixtureB=new FakePeer(listenerB,null,primarySeen,false,backupRequests)) {
            var a=new InetSocketAddress("127.0.0.1",listenerA.getLocalPort());
            var b=new InetSocketAddress("127.0.0.1",listenerB.getLocalPort());
            var available=new PeerPieceAvailability(1,Map.of());
            var owner=Thread.currentThread();
            var task=UUID.randomUUID();
            session.beginTask(77,task,info.infoHash());
            try {
                session.beginRound(owner);
                long started=System.nanoTime();
                var outcome=PeerMultiplexedRound.fetch(owner,session,info,planner,
                        List.of(0),List.of(a,b),new byte[20],available,
                        2,Duration.ofSeconds(8),()->{});
                long milliseconds=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertArrayEquals(new byte[]{1,2,3,4},outcome.verified().get(0));
                assertEquals(1,outcome.verified().size());
                assertEquals(1,backupRequests.get(),
                        "Overdue primary Block must be reissued once to another Peer");
                assertTrue(milliseconds<7000,"Backup should complete before full 8s timeout");
                assertEquals(0,session.registeredReusableSocketCount(),
                        "Sockets used in an end-game race must retire before next round");
            }finally {session.endRound(owner);session.endTask();}
        }
    }
    static class FakePeer implements AutoCloseable {
        final ExecutorService thread=Executors.newSingleThreadExecutor();
        final Future<?> future;
        FakePeer(ServerSocket listener,CountDownLatch signal,CountDownLatch waitFor,
                 boolean slow,AtomicInteger backupRequests) {
            future=thread.submit(()->{
                try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(7000);
                    var input=socket.getInputStream();
                    var output=socket.getOutputStream();
                    var offered=input.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    var parsed=WIRE.decodeHandshake(ByteBuffer.wrap(offered)).orElseThrow();
                    var peerId=new byte[20];Arrays.fill(peerId,(byte)(slow?1:2));
                    output.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],parsed.infoHash(),peerId)).array());
                    if(read(input).id()!=2)throw new IOException("Missing Interested");
                    if(waitFor!=null && !waitFor.await(4,TimeUnit.SECONDS))
                        throw new IOException("Primary never sent a request");
                    output.write(WIRE.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{(byte)0x80})).array());
                    output.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    output.flush();
                    var requested=read(input);
                    if(requested==null || requested.id()!=6)
                        throw new IOException("Missing Block REQUEST");
                    if(signal!=null)signal.countDown();
                    if(!slow)backupRequests.incrementAndGet();
                    if(slow)Thread.sleep(3500);
                    var piece=ByteBuffer.allocate(12).putInt(0).putInt(0)
                            .put(new byte[]{1,2,3,4}).array();
                    output.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,piece)).array());
                    output.flush();
                }catch(Exception ignored) {
                    // A cancelled slow endpoint is deliberately retired;
                    // it may observe connection reset while writing.
                }
            });
        }
        @Override public void close() {
            thread.shutdownNow();
            try {future.get(2,TimeUnit.SECONDS);}
            catch(Exception ignored){}
        }
    }
}
