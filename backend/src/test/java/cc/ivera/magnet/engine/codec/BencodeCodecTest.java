package cc.ivera.magnet.engine.codec;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BencodeCodecTest {
    private final BencodeDecoder decoder = new BencodeDecoder();
    private final BencodeEncoder encoder = new BencodeEncoder();

    @Test void canonicalRoundtrip() {
        var input = new DictionaryValue(Map.of(
                ByteString.ascii("a"), new IntegerValue(42),
                ByteString.ascii("b"), new ListValue(List.of(new BytesValue(new byte[] {0, (byte) 255})))
        ));
        byte[] binary = encoder.encode(input);
        assertArrayEquals(binary, encoder.encode(decoder.decode(binary)));
    }

    @Test void tracksOriginalRawInfo() throws Exception {
        byte[] torrent = "d4:infod4:name4:testee".getBytes(StandardCharsets.US_ASCII);
        TorrentDocument result = decoder.decodeTorrent(torrent);
        assertArrayEquals("d4:name4:teste".getBytes(StandardCharsets.US_ASCII), result.infoBytes());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(result.infoBytes()));
        assertEquals(40, digest.length());
    }

    @Test void rejectsMalformedAndOversizedInputs() {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("i03e".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("d1:b1:x1:a1:ye".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("5:ab".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[9 * 1024 * 1024]));
    }
}
