package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PeerPieceDownloadSessionAbortTest {
    @Test void closingRegisteredSocketUnblocksPeerHandshakeRead() throws Exception {
        byte[] contents="test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] infoHash=MessageDigest.getInstance("SHA-1").digest("abort".getBytes());
        var torrent=new TorrentInfo(HexFormat.of().formatHex(infoHash),"abort-fixture",
                List.of(new PieceLayout.FileEntry(0,"test.bin",0,contents.length)),
                contents.length,contents.length,
                MessageDigest.getInstance("SHA-1").digest(contents));
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
        var accepted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        try(var server=new ServerSocket(0)) {
            Thread peer=new Thread(()->{
                try(var socket=server.accept()) {
                    assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,
                            socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH).length);
                    accepted.countDown();
                    release.await(5,TimeUnit.SECONDS); // peer deliberately never responds
                }catch(Exception ignored){accepted.countDown();}
            },"native-abort-fixture-peer");
            peer.setDaemon(true);
            peer.start();
            Thread worker=new Thread(()->{
                try {
                    session.downloadPiece(new InetSocketAddress("127.0.0.1",server.getLocalPort()),
                            torrent,new byte[20],planner,0,Duration.ofSeconds(15));
                    failure.set(new AssertionError("Stalled Peer must not complete a Piece"));
                }catch(Throwable expected) {
                    failure.set(expected);
                }
            },"native-abort-test-worker");
            worker.start();
            try {
                assertTrue(accepted.await(2,TimeUnit.SECONDS));
                assertTrue(session.abortForThread(worker));
                worker.join(1500);
                assertFalse(worker.isAlive(),"Closing the socket should wake read promptly");
                assertInstanceOf(IOException.class,failure.get());
                assertFalse(session.abortForThread(worker),"Socket registration must be removed");
            }finally {
                release.countDown();
                worker.interrupt();
                peer.join(1000);
                worker.join(1000);
            }
        }
    }
}
