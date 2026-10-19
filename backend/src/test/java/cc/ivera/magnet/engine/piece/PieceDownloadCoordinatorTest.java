package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PieceDownloadCoordinatorTest {
    @TempDir Path root;
    private static final byte[] DATA="abcdefghijk".getBytes(StandardCharsets.US_ASCII);
    private static final List<InetSocketAddress> PEERS=List.of(
            InetSocketAddress.createUnresolved("8.8.8.8",6881),
            InetSocketAddress.createUnresolved("9.9.9.9",6881));

    private static TorrentInfo info() throws Exception {
        byte[] hashes=new byte[60];
        for(int piece=0;piece<3;piece++) {
            int from=piece*4;
            byte[] hash=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,from,Math.min(from+4,DATA.length)));
            System.arraycopy(hash,0,hashes,piece*20,20);
        }
        return new TorrentInfo("2".repeat(40),"test",
                List.of(new PieceLayout.FileEntry(0,"payload.bin",0,DATA.length)),
                DATA.length,4,hashes);
    }

    @Test void boundedRoundFallsBackAndRestartSkipsAlreadyVerifiedPieces() throws Exception {
        var torrent=info();UUID task=UUID.randomUUID();
        var state=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,101,task);
        AtomicInteger calls=new AtomicInteger();
        PieceDownloadCoordinator.Fetcher fetcher=(peer,t,identity,planner,piece,timeout)->{
            calls.incrementAndGet();
            if(peer.getHostString().equals("8.8.8.8"))throw new IOException("Peer offline");
            int from=piece*4;
            return Arrays.copyOfRange(DATA,from,Math.min(from+4,DATA.length));
        };
        var coordinator=new PieceDownloadCoordinator(state,fetcher,PEERS,
                new byte[20],2,Duration.ofSeconds(1));
        var first=coordinator.runRound(2);
        assertEquals(2,first.attemptedPieces());
        assertEquals(2,first.committedPieces());
        assertEquals(3,first.peerAttempts()); // Peer order rotates by Piece index.
        assertTrue(first.failedPieces().isEmpty());
        assertEquals(8,first.verifiedSelectedBytes());
        assertTrue(first.progressPercent()>70 && first.progressPercent()<75);

        var recovered=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,101,task);
        assertEquals(8,recovered.verifiedSelectedBytes());
        int callsBefore=calls.get();
        var continuing=new PieceDownloadCoordinator(recovered,fetcher,PEERS,
                new byte[20],2,Duration.ofSeconds(1));
        var rest=continuing.runRound(2);
        assertEquals(1,rest.attemptedPieces());
        assertEquals(1,rest.committedPieces());
        assertEquals(11,rest.verifiedSelectedBytes());
        assertEquals(100.0,rest.progressPercent());
        assertTrue(calls.get()>callsBefore);
        assertArrayEquals(DATA,Files.readAllBytes(root.resolve("payload.bin.clipfetch.part")));
        assertEquals(0,continuing.runRound(2).committedPieces());
    }

    @Test void sequentialRoundRetainsPeerPenaltyAcrossPiecesWithoutAvailabilityReports() throws Exception {
        var torrent=info();
        var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,811,UUID.randomUUID());
        var bad=PEERS.get(0);
        var attempted=new java.util.ArrayList<String>();
        var downloader=new PieceDownloadCoordinator(checkpoint,(peer,t,id,planner,piece,budget)->{
            attempted.add(piece+":"+peer.getHostString());
            if(peer.equals(bad))throw new IOException("Peer timed out");
            int start=piece*4;
            return Arrays.copyOfRange(DATA,start,Math.min(start+4,DATA.length));
        },PEERS,new byte[20],2,Duration.ofMillis(100));
        var result=downloader.runRound(2);
        assertEquals(2,result.committedPieces());
        assertEquals(3,result.peerAttempts());
        assertEquals("0:"+bad.getHostString(),attempted.get(0));
        assertEquals("1:"+PEERS.get(1).getHostString(),attempted.get(2),
                "Second Piece must prefer healthy Peer rather than retrying failed Peer");
        assertEquals(8,checkpoint.verifiedSelectedBytes());
    }

    @Test void peerTimeoutsAndDisconnectsPreserveCheckpointAcrossRetryRound() throws Exception {
        var torrent=info();
        UUID task=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,701,task);
        AtomicInteger attempts=new AtomicInteger();
        var first=new PieceDownloadCoordinator(checkpoint,(peer,t,id,planner,piece,budget)->{
            attempts.incrementAndGet();
            throw new IOException("Peer disconnected or request timed out");
        },PEERS,new byte[20],2,Duration.ofMillis(100));
        var failure=first.runRound(1);
        assertEquals(1,failure.attemptedPieces());
        assertEquals(0,failure.committedPieces());
        assertEquals(List.of(0),failure.failedPieces());
        assertEquals(2,attempts.get());
        assertEquals(0,checkpoint.verifiedSelectedBytes());

        // Simulate a later round with newly reachable peers and a reopened manifest.
        var restarted=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,701,task);
        var requested=new java.util.ArrayList<Integer>();
        var recovered=new PieceDownloadCoordinator(restarted,(peer,t,id,planner,piece,budget)->{
            requested.add(piece);
            int from=piece*4;
            return Arrays.copyOfRange(DATA,from,Math.min(from+4,DATA.length));
        },PEERS,new byte[20],2,Duration.ofSeconds(1));
        assertEquals(1,recovered.runRound(1).committedPieces());
        assertEquals(4,restarted.verifiedSelectedBytes());
        assertEquals(2,recovered.runRound(2).committedPieces());
        assertEquals(List.of(0,1,2),requested);
        assertEquals(11,new VerifiedPieceCheckpoint(torrent,Set.of(0),root,701,task)
                .verifiedSelectedBytes());
        assertEquals(0,recovered.runRound(2).attemptedPieces());
    }

    @Test void malformedPieceFromAllPeersDoesNotCreateProgress() throws Exception {
        var info=info();
        var state=new VerifiedPieceCheckpoint(info,Set.of(0),root,1,UUID.randomUUID());
        var coord=new PieceDownloadCoordinator(state,(peer,t,id,planner,piece,budget)->
                new byte[]{9,9,9,9},PEERS,new byte[20],2,Duration.ofSeconds(1));
        var result=coord.runRound(1);
        assertEquals(1,result.attemptedPieces());
        assertEquals(0,result.committedPieces());
        assertEquals(List.of(0),result.failedPieces());
        assertEquals(0,state.verifiedSelectedBytes());
        assertFalse(Files.exists(root.resolve("payload.bin.clipfetch.part")));
    }

    @Test void diskFailureIsNotMisclassifiedAsPeerFailure() throws Exception {
        var info=info();
        var state=new VerifiedPieceCheckpoint(info,Set.of(0),root,1,UUID.randomUUID());
        Files.createDirectory(root.resolve("payload.bin.clipfetch.part"));
        AtomicInteger calls=new AtomicInteger();
        var coord=new PieceDownloadCoordinator(state,(peer,t,id,planner,piece,budget)->{
            calls.incrementAndGet();
            return Arrays.copyOfRange(DATA,0,4);
        },PEERS,new byte[20],2,Duration.ofSeconds(1));
        assertThrows(IOException.class,()->coord.runRound(1));
        assertEquals(1,calls.get());
        assertEquals(0,state.verifiedSelectedBytes());
    }
}
