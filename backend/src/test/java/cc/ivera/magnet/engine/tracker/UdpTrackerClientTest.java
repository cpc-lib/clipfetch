package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UdpTrackerClientTest {
    @Test
    void loopbackFixtureAnswersConnectAndAnnounce() throws Exception {
        try (DatagramSocket fixture = new DatagramSocket(0)) {
            fixture.setSoTimeout(5000);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var server = pool.submit(() -> {
                    try {
                        for (int i = 0; i < 2; i++) {
                            var request = new DatagramPacket(new byte[512], 512);
                            fixture.receive(request);
                            ByteBuffer bytes = ByteBuffer.wrap(request.getData(), 0, request.getLength());
                            bytes.getLong();
                            int action = bytes.getInt();
                            int transaction = bytes.getInt();
                            byte[] reply = action == 0
                                    ? ByteBuffer.allocate(16).putInt(0).putInt(transaction).putLong(777).array()
                                    : ByteBuffer.allocate(26).putInt(1).putInt(transaction)
                                        .putInt(60).putInt(1).putInt(1)
                                        .put(new byte[] {8,8,8,8,0x1a,(byte)0xe1}).array();
                            fixture.send(new DatagramPacket(reply, reply.length, request.getSocketAddress()));
                        }
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                var client = new UdpTrackerClient(new TrackerAddressPolicy(true));
                var result = client.announce(new URI("udp://127.0.0.1:" + fixture.getLocalPort()),
                        new byte[20], new byte[20], 0, 1000, 0, 6881);
                assertEquals(6881, result.peers().getFirst().getPort());
                server.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void disallowsLoopbackWithoutTestOverride() {
        var policy = new TrackerAddressPolicy(false);
        assertThrows(SecurityException.class,
                () -> policy.resolve("127.0.0.1"));
    }
}
