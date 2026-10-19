package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerMultiplexedRoundIntegrationTest {
    @TempDir Path root;

    @Test void cachedPeerIsScheduledBeforeFreshCandidates() {
        var cached=new InetSocketAddress("127.0.0.1",6881);
        var fresh=new InetSocketAddress("127.0.0.1",6882);
        var ordered=PeerMultiplexedRound.prioritizeCachedPeers(
                List.of(fresh,cached),List.of(cached));
        assertEquals(List.of(cached,fresh),ordered);
    }

    @Test void missingCachedPeerIsAddedBeforeFreshCandidates() {
        var cached=new InetSocketAddress("127.0.0.1",6881);
        var fresh=new InetSocketAddress("127.0.0.1",6882);
        var ordered=PeerMultiplexedRound.prioritizeCachedPeers(
                List.of(fresh),List.of(cached));
        assertEquals(List.of(cached,fresh),ordered);
    }

    @Test void roundDeadlineDrainsInFlightInsteadOfFailingTheJob() throws Exception {
        byte[] data={11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};
        byte[] hashes=new byte[4*20];
        for(int i=0;i<4;i++) {
            byte[] sha=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(data,i*4,i*4+4));
            System.arraycopy(sha,0,hashes,i*20,20);
        }
        var torrent=new TorrentInfo("b".repeat(40),"multiplexed-drain",
                List.of(new PieceLayout.FileEntry(0,"multi.bin",0,16)),16,4,hashes);
        var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,39,UUID.randomUUID());
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0);
            var peerA=new PeerCentricCoordinatorIntegrationTest.MockPeer(a,(byte)0xa0,Set.of(0,2));
            var peerB=new StallingPeer(b,(byte)0x50,Set.of(1,3),3)) {
            var left=new InetSocketAddress("127.0.0.1",a.getLocalPort());
            var right=new InetSocketAddress("127.0.0.1",b.getLocalPort());
            var bitsA=new BitSet();bitsA.set(0);bitsA.set(2);
            var bitsB=new BitSet();bitsB.set(1);bitsB.set(3);
            var availability=new PeerPieceAvailability(4,Map.of(left,bitsA,right,bitsB));
            Thread owner=Thread.currentThread();
            byte[] localPeerId=new byte[20];
            var taskId=UUID.randomUUID();
            session.beginTask(39,taskId,torrent.infoHash());
            try {
                session.beginRound(owner);
                // The stalled Piece 3 pushes the round past its deadline. The
                // old behaviour aborted every Actor at the deadline and threw
                // "exceeded task deadline", failing the whole job. The deadline
                // now closes new claims, drains in-flight transfers and returns
                // the verified Pieces; the stalled Peer is just marked failed.
                var fetched=PeerMultiplexedRound.fetch(owner,session,torrent,planner,
                        List.of(0,1,2,3),List.of(left,right),localPeerId,
                        availability,2,Duration.ofSeconds(1),()->{});
                assertEquals(Set.of(0,1,2),fetched.verified().keySet());
                assertEquals(1,fetched.failedPeers());
                assertFalse(owner.isInterrupted(),"Owner must not be interrupted by round drain");
            }finally {session.endRound(owner);session.endTask();}
        }
    }

    /** Serves every requested Piece immediately except the stalled one, whose
     *  response never arrives inside the round deadline. */
    static final class StallingPeer implements AutoCloseable {
        private static final PeerWireCodec CODEC=new PeerWireCodec();
        private static final byte[] DATA=
                new byte[]{11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};
        final ExecutorService executor=Executors.newSingleThreadExecutor();
        final Future<?> task;
        StallingPeer(ServerSocket listener,byte bitfield,Set<Integer> allowed,int stallPiece) {
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
                    if(PeerCentricCoordinatorIntegrationTest.MockPeer.read(in).id()!=2)
                        throw new IOException("Expected interested");
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{bitfield})).array());
                    out.write(CODEC.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    out.flush();
                    PeerWireCodec.Message request;
                    while((request=PeerCentricCoordinatorIntegrationTest.MockPeer.read(in))!=null) {
                        if(request.id()!=6)continue;
                        var fields=ByteBuffer.wrap(request.payload());
                        int piece=fields.getInt(),begin=fields.getInt(),bytes=fields.getInt();
                        if(piece==stallPiece) {
                            Thread.sleep(25000); // Round deadline fires first.
                            continue;
                        }
                        var body=ByteBuffer.allocate(8+4).putInt(piece).putInt(begin)
                                .put(DATA,piece*4,4).array();
                        out.write(CODEC.encodeMessage(new PeerWireCodec.Message(7,body)).array());
                        out.flush();
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
        }
        @Override public void close(){executor.shutdownNow();}
    }

    @Test void twoTcpPeerActorsFetchDifferentPiecesOverOneSocketEach() throws Exception {
        byte[] data={11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};
        byte[] hashes=new byte[4*20];
        for(int i=0;i<4;i++) {
            byte[] sha=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(data,i*4,i*4+4));
            System.arraycopy(sha,0,hashes,i*20,20);
        }
        var torrent=new TorrentInfo("b".repeat(40),"multiplexed",
                List.of(new PieceLayout.FileEntry(0,"multi.bin",0,16)),16,4,hashes);
        var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,39,UUID.randomUUID());
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0);
            var peerA=new PeerCentricCoordinatorIntegrationTest.MockPeer(a,(byte)0xa0,Set.of(0,2));
            var peerB=new PeerCentricCoordinatorIntegrationTest.MockPeer(b,(byte)0x50,Set.of(1,3))) {
            var left=new InetSocketAddress("127.0.0.1",a.getLocalPort());
            var right=new InetSocketAddress("127.0.0.1",b.getLocalPort());
            var bitsA=new BitSet();bitsA.set(0);bitsA.set(2);
            var bitsB=new BitSet();bitsB.set(1);bitsB.set(3);
            var availability=new PeerPieceAvailability(4,Map.of(left,bitsA,right,bitsB));
            Thread owner=Thread.currentThread();
            byte[] localPeerId=new byte[20];
            var taskId=UUID.randomUUID();
            session.beginTask(39,taskId,torrent.infoHash());
            try {
                session.beginRound(owner);
                var fetched=PeerMultiplexedRound.fetch(owner,session,torrent,planner,
                        List.of(0,1,2,3),List.of(left,right),localPeerId,
                        availability,2,Duration.ofSeconds(8),()->{});
                assertEquals(4,fetched.verified().size());
                for(int p=0;p<4;p++)
                    assertArrayEquals(Arrays.copyOfRange(data,p*4,p*4+4),
                            fetched.verified().get(p));
                assertEquals(2,fetched.openedPeers());
                assertEquals(0,fetched.failedPeers());
                assertEquals(2,session.taskConnectionStats(39,taskId).openedSockets());
            }finally {session.endRound(owner);session.endTask();}
            peerA.awaitSuccess();peerB.awaitSuccess();
            assertEquals(2,peerA.requested.get());
            assertEquals(2,peerB.requested.get());
        }
    }

    @Test void optedInCoordinatorStillUsesOwnerOnlyCheckpointAndFencedGroupCommit()
            throws Exception {
        byte[] data={11,12,13,14,21,22,23,24,31,32,33,34,41,42,43,44};
        byte[] hashes=new byte[80];
        for(int i=0;i<4;i++)
            System.arraycopy(MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(data,i*4,i*4+4)),0,hashes,i*20,20);
        var torrent=new TorrentInfo("b".repeat(40),"multiplexed-owner",
                List.of(new PieceLayout.FileEntry(0,"multi.bin",0,16)),16,4,hashes);
        var taskId=UUID.randomUUID();
        var verified=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,39,taskId);
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        try(ServerSocket a=new ServerSocket(0);ServerSocket b=new ServerSocket(0);
            var peerA=new PeerCentricCoordinatorIntegrationTest.MockPeer(a,(byte)0xa0,Set.of(0,2));
            var peerB=new PeerCentricCoordinatorIntegrationTest.MockPeer(b,(byte)0x50,Set.of(1,3))) {
            var left=new InetSocketAddress("127.0.0.1",a.getLocalPort());
            var right=new InetSocketAddress("127.0.0.1",b.getLocalPort());
            var bitsA=new BitSet();bitsA.set(0);bitsA.set(2);
            var bitsB=new BitSet();bitsB.set(1);bitsB.set(3);
            var available=new PeerPieceAvailability(4,Map.of(left,bitsA,right,bitsB));
            byte[] localPeerId=new byte[20];
            Thread owner=Thread.currentThread();
            session.beginTask(39,taskId,torrent.infoHash());
            try {
                var coordinator=new PieceDownloadCoordinator(verified,
                        session::downloadPiece,List.of(left,right),localPeerId,
                        2,Duration.ofSeconds(8),2,session,available);
                coordinator.enableMultiplexedMailbox();
                coordinator.configureGroupCommit(2);
                var commits=new java.util.ArrayList<Long>();
                var report=coordinator.runRound(4,(piece,wire,total)->{
                    assertSame(owner,Thread.currentThread(),
                            "Durable fenced group commit is owned by the original task");
                    commits.add(total);
                },()->{});
                assertEquals(4,report.committedPieces());
                assertEquals(4,verified.completedPieces());
                assertEquals(16,verified.verifiedSelectedBytes());
                assertEquals(List.of(8L,16L),commits);
                assertEquals(0,verified.stagedGroupSize());
            }finally {session.endTask();}
            peerA.awaitSuccess();
            peerB.awaitSuccess();
        }
    }
}
