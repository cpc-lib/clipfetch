package cc.ivera.magnet.engine.dht;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KrpcCodecTest {
    private final KrpcCodec codec = new KrpcCodec();

    @Test void queryRoundtripPreservesBinaryTransaction() {
        byte[] tx = {(byte) 0xff, 0};
        var decoded = codec.decode(codec.encodeQuery(tx, "get_peers", new byte[20], new byte[20]));
        assertEquals('q', decoded.type());
        assertEquals("get_peers", decoded.method());
        assertArrayEquals(tx, decoded.transaction());
        assertNotNull(decoded.payload().get("info_hash"));
    }

    @Test void rejectsMalformedPacketsAndInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[1600]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {'a'}));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeQuery(new byte[]{1},
                "ping", new byte[20],new byte[20]));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeQuery(new byte[]{1},
                "find_node", new byte[19],new byte[20]));
    }
}
