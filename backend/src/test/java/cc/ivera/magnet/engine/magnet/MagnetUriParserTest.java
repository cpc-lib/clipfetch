package cc.ivera.magnet.engine.magnet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MagnetUriParserTest {
    private final MagnetUriParser parser = new MagnetUriParser();

    @Test void supportsHexAndBase32() {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        var hex = parser.parse("magnet:?xt=urn:btih:" + sha.toUpperCase() + "&dn=test&tr=https%3A%2F%2Ftracker.example%2Fa");
        assertEquals(sha, hex.infoHash());
        assertEquals("test", hex.displayName());
        assertEquals(1, hex.trackers().size());
        assertEquals("0000000000000000000000000000000000000000",
                parser.parse("magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").infoHash());
    }

    @Test void rejectsMismatchingHashesAndV2Only() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "magnet:?xt=urn:btih:" + "0".repeat(40) + "&xt=urn:btih:" + "1".repeat(40)));
        assertThrows(UnsupportedOperationException.class, () -> parser.parse(
                "magnet:?xt=urn:btmh:1220" + "a".repeat(64)));
    }

    @Test void rejectsMalformedTrackers() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "magnet:?xt=urn:btih:" + "1".repeat(40) + "&tr=file:///etc/passwd"));
    }
}
