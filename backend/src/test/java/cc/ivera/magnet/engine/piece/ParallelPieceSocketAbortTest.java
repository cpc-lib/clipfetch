package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ParallelPieceSocketAbortTest {
    @TempDir Path storage;

    @Test void cancellingOwnerClosesAllParallelChildPeerSockets() throws Exception {
        byte[] infoHash=MessageDigest.getInstance("SHA-1").digest("parallel-cancel".getBytes());
        byte[] pieces=new byte[40];
        for(int p=0;p<2;p++) {
            byte[] digest=MessageDigest.getInstance("SHA-1").digest(new byte[]{(byte)('a'+p)});
            System.arraycopy(digest,0,pieces,p*20,20);
        }
        var info=new TorrentInfo(HexFormat.of().formatHex(infoHash),"abort",
                List.of(new PieceLayout.FileEntry(0,"data.bin",0,2)),2,1,pieces);
        var state=new VerifiedPieceCheckpoint(info,Set.of(0),storage,44,UUID.randomUUID());
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
        var accepted=new CountDownLatch(2);
        var stopServers=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();

        try(ServerSocket first=new ServerSocket(0);ServerSocket second=new ServerSocket(0)) {
            Thread s1=stalledServer(first,accepted,stopServers);
            Thread s2=stalledServer(second,accepted,stopServers);
            s1.start();s2.start();
            var endpoints=List.of(
                    new InetSocketAddress("127.0.0.1",first.getLocalPort()),
                    new InetSocketAddress("127.0.0.1",second.getLocalPort()));
            var coordinator=new PieceDownloadCoordinator(state,session::downloadPiece,
                    endpoints,new byte[20],1,Duration.ofSeconds(10),2,session);
            Thread worker=new Thread(()->{
                try {
                    coordinator.runRound(2,(piece,wire,verified)->{},()->{});
                    failure.set(new AssertionError("Stopped Worker must not commit Pieces"));
                }catch(Throwable expected) {
                    failure.set(expected);
                }
            },"native-parallel-owner");
            worker.start();
            try {
                assertTrue(accepted.await(4,TimeUnit.SECONDS),
                        "Both Peer sockets must be connected before cancellation");
                assertTrue(session.abortForThread(worker),
                        "Both child sockets should be owned by the original Worker");
                worker.interrupt();
                worker.join(5000);
                assertFalse(worker.isAlive(),"Child TCP reads must terminate on parent cancellation");
                assertInstanceOf(IOException.class,failure.get());
                assertEquals(0,state.verifiedSelectedBytes());
                assertFalse(session.abortForThread(worker),"Cancelled children should be unregistered");
            }finally {
                stopServers.countDown();
                worker.interrupt();
                s1.join(1000);s2.join(1000);
            }
        }
    }

    @Test void cancelledParallelRoundDoesNotCommitLateNetworkResults() throws Exception {
        byte[] data=new byte[]{'a','b'};
        byte[] hashes=new byte[40];
        for(int i=0;i<2;i++) {
            byte[] digest=MessageDigest.getInstance("SHA-1").digest(new byte[]{data[i]});
            System.arraycopy(digest,0,hashes,i*20,20);
        }
        var info=new TorrentInfo("4".repeat(40),"late-cancel",
                List.of(new PieceLayout.FileEntry(0,"late.bin",0,2)),2,1,hashes);
        UUID task=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),storage,55,task);
        var entered=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        var peer=new InetSocketAddress("127.0.0.1",6881);
        var coordinator=new PieceDownloadCoordinator(checkpoint,(endpoint,t,id,planner,piece,timeout)->{
            entered.countDown();
            try {release.await(3,TimeUnit.SECONDS);}
            catch(InterruptedException ignored) { /* delayed result after cancellation */ }
            return new byte[]{data[piece]};
        },List.of(peer,new InetSocketAddress("127.0.0.1",6882)),
                new byte[20],1,Duration.ofSeconds(3),2,
                PieceDownloadCoordinator.ChildThreads.none());
        Thread owner=new Thread(()->{
            try {
                coordinator.runRound(2);
                failure.set(new AssertionError("Cancelled round returned successfully"));
            }catch(Throwable error) {failure.set(error);}
        },"native-late-result-test");
        owner.start();
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            owner.interrupt();
            release.countDown();
            owner.join(5000);
            assertFalse(owner.isAlive());
            assertInstanceOf(IOException.class,failure.get());
            assertEquals(0,checkpoint.verifiedSelectedBytes());
            assertEquals(0,new VerifiedPieceCheckpoint(info,Set.of(0),storage,55,task)
                    .verifiedSelectedBytes());
        }finally {
            release.countDown();
            owner.interrupt();
            owner.join(5000);
        }
    }

    private static Thread stalledServer(ServerSocket listener,CountDownLatch started,
                                        CountDownLatch release) {
        Thread thread=new Thread(()->{
            try(var socket=listener.accept()) {
                socket.setSoTimeout(1500);
                socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                started.countDown();
                release.await(6,TimeUnit.SECONDS);
            }catch(Exception ignored) {
                started.countDown();
            }
        },"native-stalled-peer-test");
        thread.setDaemon(true);
        return thread;
    }
}
