package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PeerPieceAvailabilityTest {
    @TempDir Path root;
    private static InetSocketAddress peer(String ip) {
        return InetSocketAddress.createUnresolved(ip,6881);
    }
    private static BitSet bits(int... indexes) {
        BitSet value=new BitSet();
        for(int index:indexes)value.set(index);
        return value;
    }

    @Test void rarestFirstRanksPositivePiecesAheadOfUnknownAndSkipsVerified() {
        InetSocketAddress a=peer("8.8.8.8"),b=peer("9.9.9.9"),c=peer("1.1.1.1");
        var raw=bits(0,1,2);
        var availability=new PeerPieceAvailability(5,Map.of(a,raw,b,bits(1,2),c,bits(2)));
        raw.set(4); // constructor must copy caller-owned data
        assertEquals(1,availability.knownAvailability(0));
        assertEquals(2,availability.knownAvailability(1));
        assertEquals(3,availability.knownAvailability(2));
        assertEquals(0,availability.knownAvailability(4));
        assertEquals(List.of(0,1,2,3,4),
                availability.choose(bits(0,1,2,3,4),new BitSet(),5));
        assertEquals(List.of(1,2,3),
                availability.choose(bits(0,1,2,3,4),bits(0,4),3));
        assertFalse(availability.couldHave(a,4));
        assertFalse(availability.couldHave(b,0));
        assertTrue(availability.couldHave(peer("4.4.4.4"),4)); // unknown != absent
        assertTrue(availability.confirmed(a,0));
        assertFalse(availability.confirmed(peer("4.4.4.4"),0));
        assertThrows(IllegalArgumentException.class,
                ()->new PeerPieceAvailability(3,Map.of(a,bits(5))));
    }

    @Test void selectedRarestPiecesAreFetchedOnlyFromReportedOwners() throws Exception {
        byte[] content="abcdefghijkl".getBytes(StandardCharsets.US_ASCII);
        byte[] hashes=new byte[60];
        for(int p=0;p<3;p++)System.arraycopy(
                MessageDigest.getInstance("SHA-1").digest(
                        Arrays.copyOfRange(content,p*4,p*4+4)),0,hashes,p*20,20);
        var torrent=new TorrentInfo("a".repeat(40),"availability",
                List.of(new PieceLayout.FileEntry(0,"availability.bin",0,12)),12,4,hashes);
        var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,5,UUID.randomUUID());
        InetSocketAddress a=peer("8.8.8.8"),b=peer("9.9.9.9"),c=peer("1.1.1.1");
        // Piece 2 rarest (one Peer), Piece 1 next (two), Piece 0 most common.
        var availability=new PeerPieceAvailability(3,
                Map.of(a,bits(0,1,2),b,bits(0,1),c,bits(0)));
        List<Integer> ordered=new ArrayList<>();
        var coordinator=new PieceDownloadCoordinator(checkpoint,
                (endpoint,info,id,planner,piece,deadline)->{
                    assertTrue(availability.confirmed(endpoint,piece));
                    return Arrays.copyOfRange(content,piece*4,piece*4+4);
                },List.of(a,b,c),new byte[20],3,Duration.ofSeconds(1),
                1,PieceDownloadCoordinator.ChildThreads.none(),availability);
        var result=coordinator.runRound(2,(piece,bytes,verified)->ordered.add(piece));
        assertEquals(List.of(2,1),ordered);
        assertEquals(2,result.committedPieces());
        assertEquals(8,result.verifiedSelectedBytes());
        assertEquals(0,checkpoint.verifiedPieces().get(0)?1:0);
        assertEquals(2,checkpoint.completedPieces());
    }

    @Test void aReportedAbsentPeerIsNeverContactedButUnknownPeersCanBeUsed() throws Exception {
        byte[] content="abcd".getBytes(StandardCharsets.US_ASCII);
        byte[] sha=MessageDigest.getInstance("SHA-1").digest(content);
        var info=new TorrentInfo("b".repeat(40),"availability",
                List.of(new PieceLayout.FileEntry(0,"a.bin",0,4)),4,4,sha);
        var state=new VerifiedPieceCheckpoint(info,Set.of(0),root,5,UUID.randomUUID());
        InetSocketAddress a=peer("8.8.8.8"),unknown=peer("9.9.9.9");
        var known=new PeerPieceAvailability(1,Map.of(a,new BitSet()));
        var round=new PieceDownloadCoordinator(state,
                (endpoint,t,id,planner,piece,budget)->{
                    assertEquals(unknown,endpoint);
                    return content;
                },List.of(a,unknown),new byte[20],2,Duration.ofSeconds(1),
                2,PieceDownloadCoordinator.ChildThreads.none(),known)
                .runRound(1);
        assertEquals(1,round.peerAttempts());
        assertEquals(1,round.committedPieces());
    }
}
