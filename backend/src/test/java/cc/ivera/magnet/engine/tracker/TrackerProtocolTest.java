package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TrackerProtocolTest {
    @Test void rawInfoHashIsPercentEncodedByteForByte() {
        assertEquals("%00%FF%20%2F", TrackerProtocol.percentEncode(new byte[]{0, (byte)255, 32, 47}));
    }
    @Test void compactPeersAndHttpResponse() {
        byte[] response = "d8:intervali1800e5:peers6:".getBytes(StandardCharsets.US_ASCII);
        byte[] end = new byte[]{8,8,8,8,0x1A,(byte)0xE1,'e'};
        byte[] all = new byte[response.length + end.length];
        System.arraycopy(response,0,all,0,response.length);
        System.arraycopy(end,0,all,response.length,end.length);
        var result = TrackerProtocol.parseHttpResponse(all);
        assertEquals(1800, result.intervalSeconds());
        assertEquals(6881, result.peers().getFirst().getPort());
        assertEquals("8.8.8.8", result.peers().getFirst().getHostString());
        assertThrows(IllegalArgumentException.class, () -> TrackerProtocol.compactIpv4(new byte[5]));
    }
    @Test void udpConnectAndAnnounceFieldsAreValidated() {
        byte[] request = TrackerProtocol.udpConnectRequest(12345);
        assertEquals(16, request.length);
        assertEquals(0x41727101980L, ByteBuffer.wrap(request).getLong());
        byte[] reply = ByteBuffer.allocate(16).putInt(0).putInt(12345).putLong(777).array();
        assertEquals(777, TrackerProtocol.parseUdpConnectResponse(reply,12345));
        assertThrows(IllegalArgumentException.class, () -> TrackerProtocol.parseUdpConnectResponse(reply,12346));
        byte[] announce = TrackerProtocol.udpAnnounceRequest(777,12345,new byte[20],new byte[20],0,1000,0,6881);
        assertEquals(98,announce.length);
        byte[] response = ByteBuffer.allocate(26).putInt(1).putInt(12345).putInt(60).putInt(1).putInt(1)
                .put(new byte[]{1,2,3,4,0x1A,(byte)0xE1}).array();
        assertEquals(6881, TrackerProtocol.parseUdpAnnounceResponse(response,12345).peers().getFirst().getPort());
    }
}
