package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PieceBlockPlannerTest {
    private static TorrentInfo torrent(byte[] content,int pieceSize,List<PieceLayout.FileEntry> files) throws Exception {
        int count=(content.length+pieceSize-1)/pieceSize;
        byte[] digests=new byte[count*20];
        for (int piece=0;piece<count;piece++) {
            int from=piece*pieceSize;
            byte[] digest=MessageDigest.getInstance("SHA-1")
                    .digest(java.util.Arrays.copyOfRange(content,from,
                            Math.min(content.length,from+pieceSize)));
            System.arraycopy(digest,0,digests,piece*20,20);
        }
        return new TorrentInfo("test","fixture",files,content.length,pieceSize,digests);
    }

    @Test void selectedFileIncludesBoundaryPieceAndRejectsOtherPieces() throws Exception {
        byte[] bytes="abcdefghijk".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var files=List.of(new PieceLayout.FileEntry(0,"one",0,5),
                new PieceLayout.FileEntry(1,"two",5,6));
        var info=torrent(bytes,4,files);
        var planner=new PieceBlockPlanner(info,Set.of(1));
        assertEquals(3,planner.pieceCount());
        assertFalse(planner.wantedPieces().get(0));
        assertTrue(planner.wantedPieces().get(1));
        assertTrue(planner.wantedPieces().get(2));
        assertEquals(4,planner.blocks(1).getFirst().length());
        assertEquals(3,planner.blocks(2).getFirst().length());
        assertThrows(IllegalArgumentException.class,()->planner.blocks(0));
        assertTrue(PieceHashVerifier.verify(info,1,new byte[]{'e','f','g','h'}));
        assertTrue(PieceHashVerifier.verify(info,2,new byte[]{'i','j','k'}));
        assertFalse(PieceHashVerifier.verify(info,2,new byte[]{'i','j','x'}));
        assertFalse(PieceHashVerifier.verify(info,2,new byte[]{'i','j'}));
        assertThrows(IllegalArgumentException.class,()->PieceHashVerifier.verify(info,3,new byte[]{1}));
    }

    @Test void blockRequestsAreLimitedTo16KiBAndCoverPieceExactly() throws Exception {
        byte[] content=new byte[33000];
        var info=torrent(content,33000,List.of(new PieceLayout.FileEntry(0,"large",0,33000)));
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var blocks=planner.blocks(0);
        assertEquals(3,blocks.size());
        assertEquals(16384,blocks.get(0).length());
        assertEquals(16384,blocks.get(1).length());
        assertEquals(232,blocks.get(2).length());
        assertEquals(32768,blocks.get(2).begin());
        assertEquals(33000,blocks.stream().mapToInt(PieceBlockPlanner.Block::length).sum());
        assertTrue(PieceHashVerifier.verify(info,0,content));
        var copy=planner.wantedPieces();
        copy.clear();
        assertTrue(planner.wantedPieces().get(0));
    }
}
