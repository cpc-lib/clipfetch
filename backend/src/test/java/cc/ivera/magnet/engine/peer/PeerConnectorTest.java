package cc.ivera.magnet.engine.peer;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PeerConnectorTest {
    @Test void verifiesInfoHashAgainstLocalFixture() throws Exception {
        byte[] hash = fill((byte)3);
        byte[] localId = fill((byte)2);
        try (ServerSocket server = new ServerSocket(0)) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var worker = pool.submit(() -> {
                    try (var socket = server.accept()) {
                        var incoming = socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        if (incoming.length != PeerWireCodec.HANDSHAKE_LENGTH)
                            throw new AssertionError("No handshake");
                        var response = new PeerWireCodec.Handshake(new byte[8], hash, fill((byte)5));
                        socket.getOutputStream().write(new PeerWireCodec().encodeHandshake(response).array());
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                var result = new PeerConnector(new TrackerAddressPolicy(true)).handshake(
                        new InetSocketAddress("127.0.0.1", server.getLocalPort()), hash, localId,
                        Duration.ofSeconds(3));
                assertArrayEquals(hash, result.infoHash());
                worker.get(5, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void rejectsMismatchedInfoHash() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var worker = pool.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        var response = new PeerWireCodec.Handshake(new byte[8], fill((byte)6), fill((byte)5));
                        socket.getOutputStream().write(new PeerWireCodec().encodeHandshake(response).array());
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                assertThrows(java.io.IOException.class, () ->
                        new PeerConnector(new TrackerAddressPolicy(true)).handshake(
                                new InetSocketAddress("127.0.0.1", server.getLocalPort()),
                                fill((byte)3), fill((byte)2), Duration.ofSeconds(3)));
                worker.get(5, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
    private static byte[] fill(byte value) {
        byte[] bytes = new byte[20]; Arrays.fill(bytes, value); return bytes;
    }
}
