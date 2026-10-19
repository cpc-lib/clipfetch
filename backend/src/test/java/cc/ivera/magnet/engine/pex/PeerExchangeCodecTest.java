package cc.ivera.magnet.engine.pex;

import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.ByteString;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PeerExchangeCodecTest {
    private final PeerExchangeCodec codec=new PeerExchangeCodec();

    @Test void compactIpv4PexAndFlags() {
        var data=new DictionaryValue(Map.of(
                ByteString.ascii("added"),new BytesValue(new byte[]{8,8,8,8,26,(byte)225}),
                ByteString.ascii("added.f"),new BytesValue(new byte[]{2}),
                ByteString.ascii("dropped"),new BytesValue(new byte[]{1,1,1,1,0,80})
        ));
        var decoded=codec.decode(new BencodeEncoder().encode(data));
        assertEquals(1,decoded.added().size());
        assertEquals("8.8.8.8",decoded.added().getFirst().getHostString());
        assertEquals(6881,decoded.added().getFirst().getPort());
        assertEquals(1,decoded.dropped().size());
    }
    @Test void rejectsMalformedUnboundedOrMismatchedFlags() {
        var bad=new DictionaryValue(Map.of(
                ByteString.ascii("added"),new BytesValue(new byte[]{1,2,3,4,5,6}),
                ByteString.ascii("added.f"),new BytesValue(new byte[]{1,2})
        ));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new BencodeEncoder().encode(bad)));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new byte[33000]));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new BencodeEncoder().encode(
                new DictionaryValue(Map.of(ByteString.ascii("added"),new BytesValue(new byte[5]))))));
    }
}
