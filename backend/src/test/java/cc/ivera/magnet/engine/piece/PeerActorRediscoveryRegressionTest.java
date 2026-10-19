package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for production NativeWorkerJobManager failure:
 * ThreadPoolExecutor.setMaximumPoolSize(16) while persistent actor core=64.
 */
class PeerActorRediscoveryRegressionTest {
    @TempDir Path root;
    @Test void activeRoundRefreshExpandsLogicalSlotsWithoutShrinkingRetained64CorePool()
            throws Exception {
        byte[] payload={11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};
        byte[] hashes=new byte[80];
        for(int piece=0;piece<4;piece++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(payload,piece*4,piece*4+4)),0,hashes,piece*20,20);
        var torrent=new TorrentInfo("b".repeat(40),"actor-refresh-regression",
                List.of(new PieceLayout.FileEntry(0,"actor-refresh.bin",0,16)),16,4,hashes);
        var task=UUID.randomUUID();
        var state=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,83,task);
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0);
            var fixtureA=new PeerCentricCoordinatorIntegrationTest.MockPeer(
                    a,(byte)0xa0,Set.of(0,2));
            var fixtureB=new PeerCentricCoordinatorIntegrationTest.MockPeer(
                    b,(byte)0x50,Set.of(1,3))) {
            var newA=new InetSocketAddress("127.0.0.1",a.getLocalPort());
            var newB=new InetSocketAddress("127.0.0.1",b.getLocalPort());
            var original=InetSocketAddress.createUnresolved("192.0.2.10",6881);
            var bitfieldA=new BitSet();bitfieldA.set(0);bitfieldA.set(2);
            var bitfieldB=new BitSet();bitfieldB.set(1);bitfieldB.set(3);
            var availability=new PeerPieceAvailability(4,
                    Map.of(newA,bitfieldA,newB,bitfieldB));
            var health=new PeerHealthTracker(List.of(original));
            health.failure(original);
            health.failure(original);
            var refreshed=new AtomicInteger();
            session.beginTask(83,task,torrent.infoHash());
            try {
                var retained=session.borrowActors(Thread.currentThread(),64);
                assertEquals(64,retained.getCorePoolSize());
                var coordinator=new PieceDownloadCoordinator(state,
                        session::downloadPiece,List.of(original),new byte[20],2,
                        Duration.ofSeconds(3),16,session,availability);
                coordinator.useRoundHealth(health);
                coordinator.enablePeerRefresh(()->{
                    refreshed.incrementAndGet();
                    return List.of(newA,newB);
                },16);
                var result=assertDoesNotThrow(()->coordinator.runRound(4));
                assertEquals(1,refreshed.get(),"Active coordinator must take refresh branch");
                assertTrue(coordinator.performance().workerSlots()>=2,
                        "Refreshed Peers should increase this round's logical slots");
                assertTrue(retained.getMaximumPoolSize()>=retained.getCorePoolSize(),
                        "Expanded persistent pool must obey JVM invariant");
                assertEquals(64,retained.getCorePoolSize());
                assertTrue(result.committedPieces()>0,
                        "Fresh peers must continue verified Piece downloads");
                assertEquals(result.committedPieces(),state.completedPieces());
            }finally {session.endTask();}
            assertEquals(0,session.registeredSocketCount(),
                    "Task cleanup must close the Peer socket registrations");
            // This regression targets pool resizing. Rediscovery may stop
            // before either mock Peer is given all of its Pieces, so waiting
            // for a peer-side EOF is not a reliable scheduler assertion.
        }
    }
}
