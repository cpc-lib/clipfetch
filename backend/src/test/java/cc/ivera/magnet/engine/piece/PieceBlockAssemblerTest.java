package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PieceBlockAssemblerTest {
    private static TorrentInfo torrent(byte[] data) throws Exception {
        return new TorrentInfo("00", "fixture",
                List.of(new PieceLayout.FileEntry(0, "fixture.bin", 0, data.length)),
                data.length, data.length, MessageDigest.getInstance("SHA-1").digest(data));
    }

    @Test void outOfOrderAndIdenticalDuplicatesReconstructVerifiedPiece() throws Exception {
        byte[] content = new byte[PieceBlockPlanner.BLOCK_BYTES * 2 + 9];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        var info = torrent(content);
        var planner = new PieceBlockPlanner(info, Set.of(0));
        var assembler = new PieceBlockAssembler(planner, 0);
        byte[] second = Arrays.copyOfRange(content, 16384, 32768);
        byte[] last = Arrays.copyOfRange(content, 32768, content.length);
        byte[] first = Arrays.copyOfRange(content, 0, 16384);

        assembler.accept(0, 32768, last);
        assembler.accept(0, 16384, second);
        assembler.accept(0, 16384, second.clone());
        assertEquals(2, assembler.receivedBlocks());
        assertFalse(assembler.isComplete());
        assertThrows(IllegalStateException.class, () -> assembler.verifiedPiece(info));
        assembler.accept(0, 0, first);
        assertTrue(assembler.isComplete());
        byte[] returned = assembler.verifiedPiece(info);
        assertArrayEquals(content, returned);
        returned[0]++;
        assertArrayEquals(content, assembler.verifiedPiece(info));
    }

    @Test void rejectsConflictsMisalignmentOtherPieceAndBadSha1() throws Exception {
        byte[] content = new byte[16386];
        var info = torrent(content);
        var assembler = new PieceBlockAssembler(new PieceBlockPlanner(info, Set.of(0)), 0);
        byte[] first = Arrays.copyOfRange(content, 0, 16384);
        assembler.accept(0, 0, first);
        first[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(0, 0, first));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(0, 1, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(1, 16384, new byte[2]));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(0, 16384, new byte[1]));
        assembler.accept(0, 16384, new byte[]{1,2});
        assertThrows(IllegalArgumentException.class, () -> assembler.verifiedPiece(info));
    }

    @Test void boundsMemoryPerPiece() throws Exception {
        byte[] digest = new byte[20];
        int oversized = PieceBlockAssembler.MAX_BUFFERED_PIECE_BYTES + 1;
        var info = new TorrentInfo("00", "fixture",
                List.of(new PieceLayout.FileEntry(0, "file", 0, oversized)),
                oversized, oversized, digest);
        var planner = new PieceBlockPlanner(info, Set.of(0));
        assertThrows(IllegalArgumentException.class, () -> new PieceBlockAssembler(planner, 0));
    }
}
