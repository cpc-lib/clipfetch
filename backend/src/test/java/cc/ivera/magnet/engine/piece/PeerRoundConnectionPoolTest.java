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
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class PeerRoundConnectionPoolTest {
    @TempDir Path temp;
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static final byte[] DATA="abcdefgh".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOCAL=new byte[20];

    private TorrentInfo info() throws Exception {
        byte[] hash=new byte[40];
        for(int index=0;index<2;index++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,index*4,index*4+4)),0,hash,index*20,20);
        byte[] infoHash=MessageDigest.getInstance("SHA-1").digest(
                "reuse-fixture".getBytes(StandardCharsets.US_ASCII));
        return new TorrentInfo(HexFormat.of().formatHex(infoHash),"reuse",
                List.of(new PieceLayout.FileEntry(0,"reuse.bin",0,8)),8,4,hash);
    }
    private VerifiedPieceCheckpoint checkpoint() throws Exception {
        return new VerifiedPieceCheckpoint(info(),Set.of(0),temp,15,UUID.randomUUID());
    }

    @Test void fastReusedPeersKeepSocketsLongerButSlowP95RetiresEarly() {
        long secs=java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        assertEquals(30*secs,PeerRoundConnectionPool.cacheLifetimeNanos(0,15));
        assertEquals(30*secs,PeerRoundConnectionPool.cacheLifetimeNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25),0));
        assertEquals(75*secs,PeerRoundConnectionPool.cacheLifetimeNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(25),3));
        assertEquals(30*secs,PeerRoundConnectionPool.cacheLifetimeNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500),3));
        assertEquals(15*secs,PeerRoundConnectionPool.cacheLifetimeNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(900),4));
    }

    @Test void twoVerifiedPiecesShareOneHandshakeAndSocketIsClosedAtRoundEnd() throws Exception {
        var state=checkpoint();
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                    List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                    LOCAL,1,Duration.ofSeconds(3),1,session);
            var round=coordinator.runRound(2);
            assertEquals(2,round.committedPieces());
            assertEquals(8,round.verifiedSelectedBytes());
            fixture.awaitRequests(2);
            assertEquals(1,fixture.connections.get(),"Both Pieces should reuse one TCP handshake");
            fixture.awaitClosed(1);
            var counters=session.reusableSocketCounters();
            assertEquals(1,counters.connected());
            assertEquals(1,counters.closed());
            assertEquals(0,counters.ioFailures());
            assertEquals(0,counters.registered());
            assertEquals(8,state.verifiedSelectedBytes());
        }
    }

    @Test void successiveRoundsDoNotReuseSocketsAcrossTaskWorkBoundaries() throws Exception {
        var state=checkpoint();
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                    List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                    LOCAL,1,Duration.ofSeconds(3),1,session);
            assertEquals(1,coordinator.runRound(1).committedPieces());
            assertEquals(1,coordinator.runRound(1).committedPieces());
            fixture.awaitRequests(2);
            fixture.awaitClosed(2);
            assertEquals(2,fixture.connections.get(),"Each round must start a fresh TCP session");
            assertEquals(8,state.verifiedSelectedBytes());
        }
    }

    @Test void sameContinuousTaskKeepsOneAuthenticatedSocketAcrossRoundsAndClosesOnEnd()
            throws Exception {
        var state=checkpoint();
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var info=state.torrentInfo();
            UUID task=UUID.randomUUID();
            session.beginTask(15,task,info.infoHash());
            try {
                var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                        List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                        LOCAL,1,Duration.ofSeconds(3),2,session);
                assertEquals(1,coordinator.runRound(1).committedPieces());
                assertEquals(4,session.receivedPayloadBytes(15,task),
                        "Accepted Piece blocks must be visible before another round commits");
                assertEquals(0,session.receivedPayloadBytes(16,task),
                        "Other users cannot read another task's transfer counters");
                assertEquals(0,session.receivedPayloadBytes(15,UUID.randomUUID()));
                assertEquals(1,session.registeredReusableSocketCount(),
                        "A continuous job must retain its authenticated TCP session");
                var before=session.taskConnectionStats(15,task);
                assertEquals(1,before.openedSockets());
                assertEquals(0,before.crossRoundReuseHits());
                assertEquals(1,before.idleSockets());
                assertEquals(0,session.taskConnectionStats(16,task).openedSockets());
                assertEquals(1,coordinator.runRound(1).committedPieces());
                assertEquals(8,session.receivedPayloadBytes(15,task));
                var after=session.taskConnectionStats(15,task);
                assertEquals(1,after.openedSockets(),"Second round must not open another TCP socket");
                assertEquals(1,after.reuseHits());
                assertEquals(1,after.crossRoundReuseHits(),
                        "The reuse counter must distinguish an actual cross-round cache hit");
                fixture.awaitRequests(2);
                assertEquals(1,fixture.connections.get(),
                        "Second Piece round must reuse the same Peer handshake");
            }finally {session.endTask();}
            assertEquals(0,session.receivedPayloadBytes(15,task),
                    "Task telemetry must be released when the owned session terminates");
            assertEquals(0,session.taskConnectionStats(15,task).openedSockets());
            fixture.awaitClosed(1);
            assertEquals(0,session.registeredSocketCount());
        }
    }

    @Test void distinctUsersOrTasksCannotHijackAnActiveTaskPeerScope() throws Exception {
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var hash=info().infoHash();
        session.beginTask(15,first,hash);
        try {
            assertThrows(IllegalStateException.class,
                    ()->session.beginTask(16,first,hash));
            assertThrows(IllegalStateException.class,
                    ()->session.beginTask(15,second,hash));
            assertThrows(IllegalStateException.class,
                    ()->session.beginTask(15,first,hash));
        }finally {session.endTask();}
        assertEquals(0,session.registeredSocketCount());
        session.beginTask(16,second,hash);
        session.endTask();
    }

    @Test void validBitfieldAbsenceDoesNotCloseHealthyReusableTcpConnection() throws Exception {
        var state=checkpoint();
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA,null,(byte)0x80)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var task=UUID.randomUUID();
            session.beginTask(15,task,state.torrentInfo().infoHash());
            try {
                var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                        List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                        LOCAL,1,Duration.ofSeconds(3),1,session);
                var result=coordinator.runRound(2);
                assertEquals(1,result.committedPieces());
                assertEquals(List.of(1),result.failedPieces());
                assertEquals(1,fixture.requests.get(),
                        "Unavailable second Piece must not issue wire requests");
                assertEquals(1,session.registeredReusableSocketCount(),
                        "A healthy Peer must stay cached even if it lacks this Piece");
                assertEquals(0,session.reusableSocketCounters().ioFailures(),
                        "Bitfield absence is not a transport failure");
                assertEquals(1,session.taskConnectionStats(15,task).openedSockets());
            }finally {session.endTask();}
            fixture.awaitClosed(1);
            assertEquals(0,session.registeredSocketCount());
        }
    }

    @Test void disabledReusePreservesOneConnectionPerPiece() throws Exception {
        var state=checkpoint();
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
            var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                    List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                    LOCAL,1,Duration.ofSeconds(3),1,session);
            assertEquals(2,coordinator.runRound(2).committedPieces());
            fixture.awaitRequests(2);
            fixture.awaitClosed(2);
            assertEquals(2,fixture.connections.get());
        }
    }

    @Test void cancellingAnIdleOrBlockedCachedSocketStopsTheOwnedRound() throws Exception {
        var state=checkpoint();
        var secondRequested=new CountDownLatch(1);
        try(ServerSocket listener=new ServerSocket(0);
            var fixture=new PeerFixture(listener,DATA,secondRequested)) {
            var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
            var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                    List.of(new InetSocketAddress("127.0.0.1",listener.getLocalPort())),
                    LOCAL,1,Duration.ofSeconds(5),1,session);
            var failure=new AtomicReference<Throwable>();
            Thread worker=new Thread(()->{
                try {
                    coordinator.runRound(2);
                    failure.set(new AssertionError("Cancelled Piece round cannot succeed"));
                }catch(Throwable e){failure.set(e);}
            },"native-round-reuse-cancel");
            worker.start();
            try {
                assertTrue(secondRequested.await(3,TimeUnit.SECONDS),
                        "The second Piece must use the previously connected Peer socket");
                assertEquals(1,session.registeredReusableSocketCount(),
                        "A reused handshake must retain exactly one registered socket");
                assertEquals(1,session.registeredSocketCount());
                assertTrue(session.abortForThread(worker));
                worker.interrupt();
                worker.join(3000);
                assertFalse(worker.isAlive());
                assertEquals(0,session.registeredSocketCount(),
                        "Cancelled reusable sockets must be unregistered after worker exit");
                assertInstanceOf(IOException.class,failure.get());
                assertEquals(4,state.verifiedSelectedBytes(),
                        "The first verified Piece is durable, the second cannot commit");
                assertEquals(1,fixture.connections.get());
            }finally {
                fixture.releaseSecond();
                worker.interrupt();
                worker.join(1000);
            }
        }
    }

    private static PeerWireCodec.Message readMessage(InputStream in) throws Exception {
        byte[] len=in.readNBytes(4);
        if(len.length==0)return null;
        if(len.length!=4)throw new EOFException("Truncated Peer frame");
        int size=ByteBuffer.wrap(len).getInt();
        if(size<0 || size>64*1024)throw new IOException("Invalid fixture Peer length");
        byte[] data=in.readNBytes(size);
        if(data.length!=size)throw new EOFException("Truncated frame");
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(size+4).putInt(size).put(data).array())).orElseThrow();
    }

    private static final class PeerFixture implements AutoCloseable {
        final AtomicInteger connections=new AtomicInteger(),requests=new AtomicInteger(),closed=new AtomicInteger();
        final CountDownLatch requestsSeen=new CountDownLatch(2),closing=new CountDownLatch(2);
        final CountDownLatch secondRequested;
        final CountDownLatch releaseSecond=new CountDownLatch(1);
        final ServerSocket listener;
        final ExecutorService server=Executors.newSingleThreadExecutor();
        final Future<?> service;

        PeerFixture(ServerSocket listener,byte[] data) {this(listener,data,null,(byte)0xc0);}
        PeerFixture(ServerSocket listener,byte[] data,CountDownLatch secondRequested) {
            this(listener,data,secondRequested,(byte)0xc0);
        }
        PeerFixture(ServerSocket listener,byte[] data,CountDownLatch secondRequested,
                    byte advertisedBitfield) {
            this.listener=listener;this.secondRequested=secondRequested;
            service=server.submit(()->{
                try {
                    listener.setSoTimeout(4000);
                    while(!Thread.currentThread().isInterrupted()) {
                        try(Socket socket=listener.accept()) {
                            connections.incrementAndGet();
                            socket.setSoTimeout(3500);
                            serve(socket,data,advertisedBitfield);
                            closed.incrementAndGet();
                            closing.countDown();
                        }
                    }
                }catch(SocketException | SocketTimeoutException ignored) {
                    // Fixture stops with test cancellation or after its bounded accept timeout.
                }catch(Exception e) {throw new RuntimeException(e);}
            });
        }
        void serve(Socket socket,byte[] data,byte advertisedBitfield) throws Exception {
            var in=socket.getInputStream();
            var out=socket.getOutputStream();
            byte[] bytes=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
            assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,bytes.length);
            var handshake=WIRE.decodeHandshake(ByteBuffer.wrap(bytes)).orElseThrow();
            byte[] remote=new byte[20];Arrays.fill(remote,(byte)9);
            out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                    new byte[8],handshake.infoHash(),remote)).array());
            out.flush();
            assertEquals(2,readMessage(in).id());
            out.write(WIRE.encodeMessage(new PeerWireCodec.Message(5,new byte[]{advertisedBitfield})).array());
            out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
            out.flush();
            while(true) {
                var frame=readMessage(in);
                if(frame==null)break;
                if(frame.id()!=6)continue;
                var request=ByteBuffer.wrap(frame.payload());
                int piece=request.getInt(),offset=request.getInt(),length=request.getInt();
                assertEquals(0,offset);
                assertEquals(4,length);
                requests.incrementAndGet();
                requestsSeen.countDown();
                if(secondRequested!=null && piece==1) {
                    secondRequested.countDown();
                    releaseSecond.await(3,TimeUnit.SECONDS); // cancel during blocked Peer read
                }
                byte[] payload=ByteBuffer.allocate(8+length)
                        .putInt(piece).putInt(offset)
                        .put(Arrays.copyOfRange(data,piece*4,piece*4+length)).array();
                try {
                    out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7,payload)).array());
                    out.flush();
                }catch(SocketException disconnected) {break;}
            }
        }
        void awaitRequests(int n)throws InterruptedException {
            assertTrue(requestsSeen.await(3,TimeUnit.SECONDS));
            assertEquals(n,requests.get());
        }
        void awaitClosed(int n)throws InterruptedException {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(closed.get()<n && System.nanoTime()<end)Thread.sleep(10);
            assertEquals(n,closed.get());
        }
        void releaseSecond(){releaseSecond.countDown();}
        @Override public void close()throws Exception {
            releaseSecond();
            listener.close();
            server.shutdownNow();
            try {service.get(2,TimeUnit.SECONDS);}
            catch(CancellationException | ExecutionException ignored){}
            catch(TimeoutException e){server.shutdownNow();}
        }
    }
}
