package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedPieceFileStoreTest {
    @TempDir Path scratch;

    private static TorrentInfo torrent(byte[] bytes,List<PieceLayout.FileEntry> files) throws Exception {
        return new TorrentInfo("00","fixture",files,bytes.length,bytes.length,
                MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    @Test void verifiedPieceCrossingFileBoundaryWritesBothSelectedFiles() throws Exception {
        byte[] content="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(content,List.of(
                new PieceLayout.FileEntry(0,"nested/a.bin",0,3),
                new PieceLayout.FileEntry(1,"nested/b.bin",3,5)));
        Path root=scratch.resolve("account-1-task-one");
        var store=new VerifiedPieceFileStore(info,Set.of(0,1),root);
        assertEquals(8,store.persistVerifiedPiece(0,content));
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(root.resolve("nested/a.bin.clipfetch.part")));
        assertArrayEquals("defgh".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(root.resolve("nested/b.bin.clipfetch.part")));
    }

    @Test void onlySelectedFileIsWrittenAndCorruptDataNeverTouchesDisk() throws Exception {
        byte[] content="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(content,List.of(
                new PieceLayout.FileEntry(0,"folder/a.bin",0,3),
                new PieceLayout.FileEntry(1,"folder/b.bin",3,5)));
        Path root=scratch.resolve("account-2-task-two");
        var store=new VerifiedPieceFileStore(info,Set.of(1),root);
        assertThrows(IOException.class,()->store.persistVerifiedPiece(0,
                "abcdxxxx".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(Files.exists(root.resolve("folder/b.bin.clipfetch.part")));
        assertEquals(5,store.persistVerifiedPiece(0,content));
        assertFalse(Files.exists(root.resolve("folder/a.bin.clipfetch.part")));
        assertArrayEquals("defgh".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(root.resolve("folder/b.bin.clipfetch.part")));
    }

    @Test void rejectsFileTraversalBeforeAnyWrite() throws Exception {
        byte[] content="data".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(content,List.of(new PieceLayout.FileEntry(0,"../escape",0,4)));
        var store=new VerifiedPieceFileStore(info,Set.of(0),scratch.resolve("safe"));
        assertThrows(IOException.class,()->store.persistVerifiedPiece(0,content));
        assertFalse(Files.exists(scratch.resolve("escape.clipfetch.part")));
    }

    @Test void finalizeRenamesSelectedFilesAndStaysIdempotent() throws Exception {
        byte[] content="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(content,List.of(
                new PieceLayout.FileEntry(0,"nested/a.bin",0,3),
                new PieceLayout.FileEntry(1,"nested/b.bin",3,5)));
        Path root=scratch.resolve("account-3-task-three");
        var store=new VerifiedPieceFileStore(info,Set.of(0,1),root);
        assertEquals(8,store.persistVerifiedPiece(0,content));
        store.finalizeVerifiedFiles();
        assertFalse(Files.exists(root.resolve("nested/a.bin.clipfetch.part")));
        assertFalse(Files.exists(root.resolve("nested/b.bin.clipfetch.part")));
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(root.resolve("nested/a.bin")));
        assertArrayEquals("defgh".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(root.resolve("nested/b.bin")));
        // A repeated run must not truncate or drop already finalized bytes.
        store.finalizeVerifiedFiles();
        assertEquals(5,Files.size(root.resolve("nested/b.bin")));
        // Recovery keeps reading the verified bytes after the rename.
        assertArrayEquals(content,store.readForRecovery(0,null));
    }

    @Test void finalizeSkipsUnselectedFiles() throws Exception {
        byte[] content="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(content,List.of(
                new PieceLayout.FileEntry(0,"folder/a.bin",0,3),
                new PieceLayout.FileEntry(1,"folder/b.bin",3,5)));
        Path root=scratch.resolve("account-4-task-four");
        var store=new VerifiedPieceFileStore(info,Set.of(0),root);
        assertEquals(3,store.persistVerifiedPiece(0,content));
        store.finalizeVerifiedFiles();
        assertTrue(Files.isRegularFile(root.resolve("folder/a.bin")));
        assertFalse(Files.exists(root.resolve("folder/a.bin.clipfetch.part")));
        assertFalse(Files.exists(root.resolve("folder/b.bin")));
        assertFalse(Files.exists(root.resolve("folder/b.bin.clipfetch.part")));
    }
}
