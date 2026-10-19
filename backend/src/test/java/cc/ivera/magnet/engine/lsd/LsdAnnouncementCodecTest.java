package cc.ivera.magnet.engine.lsd;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class LsdAnnouncementCodecTest {
    private final LsdAnnouncementCodec codec=new LsdAnnouncementCodec();
    @Test void announcesAndFindsMatchingPrivateLanSource() throws Exception {
        byte[] hash=new byte[20];hash[0]=0x12;
        byte[] packet=codec.encode(hash,6881,"aabbccddeeff0123");
        assertTrue(new String(packet,java.nio.charset.StandardCharsets.US_ASCII)
                .startsWith("BT-SEARCH * HTTP/1.1"));
        var address=codec.parse(packet,InetAddress.getByName("192.168.1.44"),hash,"othercookie").orElseThrow();
        assertEquals("192.168.1.44",address.getAddress().getHostAddress());
        assertEquals(6881,address.getPort());
    }
    @Test void blocksPublicSenderWrongHashAndOwnCookie() throws Exception {
        byte[] hash=new byte[20];
        byte[] packet=codec.encode(hash,6881,"aabbccddeeff0123");
        assertTrue(codec.parse(packet,InetAddress.getByName("8.8.8.8"),hash,"z").isEmpty());
        assertTrue(codec.parse(packet,InetAddress.getByName("10.1.2.3"),new byte[]{1,2,3},"z").isEmpty());
        assertTrue(codec.parse(packet,InetAddress.getByName("10.1.2.3"),hash,"aabbccddeeff0123").isEmpty());
    }
}
