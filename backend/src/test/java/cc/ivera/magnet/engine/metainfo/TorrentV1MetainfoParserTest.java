package cc.ivera.magnet.engine.metainfo;

import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TorrentV1MetainfoParserTest {
    private final BencodeEncoder encoder = new BencodeEncoder();

    private BytesValue bytes(String value) { return new BytesValue(value.getBytes(StandardCharsets.UTF_8)); }
    private DictionaryValue file(int size, String name) {
        return new DictionaryValue(Map.of(
                ByteString.ascii("length"), new IntegerValue(size),
                ByteString.ascii("path"), new ListValue(List.of(bytes(name)))
        ));
    }
    private byte[] metadata(ListValue fileList, int hashCount) {
        DictionaryValue info = new DictionaryValue(Map.of(
                ByteString.ascii("name"), bytes("folder"),
                ByteString.ascii("files"), fileList,
                ByteString.ascii("piece length"), new IntegerValue(8),
                ByteString.ascii("pieces"), new BytesValue(new byte[hashCount * 20])
        ));
        return encoder.encode(new DictionaryValue(Map.of(ByteString.ascii("info"), info)));
    }

    @Test void calculatesOnlySelectedVerifiedPieceOverlaps() {
        byte[] torrent = metadata(new ListValue(List.of(file(5, "a.bin"), file(15, "b.bin"))), 3);
        var parsed = new TorrentV1MetainfoParser().parse(torrent);
        assertEquals(20, parsed.totalBytes());
        assertEquals(3, parsed.pieceLayout().pieceCount());
        PieceLayout layout = parsed.pieceLayout();
        BitSet verify = new BitSet();
        verify.set(0);
        assertEquals(5, layout.selectedTotalBytes(Set.of(0)));
        assertEquals(5, layout.verifiedSelectedBytes(Set.of(0), verify));
        assertEquals(15, layout.selectedTotalBytes(Set.of(1)));
        assertEquals(3, layout.verifiedSelectedBytes(Set.of(1), verify));
        assertEquals(20.0, layout.progressPercent(Set.of(1), verify), 0.0001);
        verify.set(1); verify.set(2);
        assertEquals(15, layout.verifiedSelectedBytes(Set.of(1), verify));
        assertEquals("folder/a.bin", parsed.files().get(0).path());
        assertEquals(40, parsed.infoHash().length());
    }

    @Test void rejectsUnsafePathsAndHashCountMismatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new TorrentV1MetainfoParser().parse(metadata(new ListValue(List.of(file(5, ".."))), 1)));
        assertThrows(IllegalArgumentException.class, () ->
                new TorrentV1MetainfoParser().parse(metadata(new ListValue(List.of(file(20, "good.bin"))), 2)));
    }

    @Test void sameContentSelectionNeedsIndependentBitmaps() {
        PieceLayout layout = new TorrentV1MetainfoParser()
                .parse(metadata(new ListValue(List.of(file(5, "a"), file(15, "b"))), 3))
                .pieceLayout();
        BitSet a = new BitSet(), b = new BitSet();
        a.set(0);
        b.set(0); b.set(1);
        assertEquals(3, layout.verifiedSelectedBytes(Set.of(1), a));
        assertEquals(11, layout.verifiedSelectedBytes(Set.of(1), b));
        assertEquals(5, layout.verifiedSelectedBytes(Set.of(0), a));
    }
}
