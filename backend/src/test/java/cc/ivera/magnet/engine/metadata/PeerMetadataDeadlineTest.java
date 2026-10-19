package cc.ivera.magnet.engine.metadata;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PeerMetadataDeadlineTest {
    @Test void slowTrickleOfHandshakeBytesCannotExtendAbsoluteDeadline() throws Exception {
        try (ServerSocket server=new ServerSocket(0)) {
            var pool=Executors.newSingleThreadExecutor();
            try {
                var worker=pool.submit(() -> {
                    try (var socket=server.accept()) {
                        socket.setSoTimeout(3000);
                        socket.getInputStream().readNBytes(68);
                        // Each byte arrives before the previous per-read socket timeout.
                        // Without an absolute deadline the client can remain blocked indefinitely.
                        for (int i=0;i<22;i++) {
                            socket.getOutputStream().write('x');
                            socket.getOutputStream().flush();
                            Thread.sleep(80);
                        }
                    } catch (Exception ignored) {
                        // Client is expected to close the TCP connection at its deadline.
                    }
                });
                var client=new PeerMetadataSession(new TrackerAddressPolicy(true));
                long started=System.nanoTime();
                assertThrows(IOException.class, () -> client.fetch(
                        new InetSocketAddress("127.0.0.1",server.getLocalPort()),
                        new byte[20],new byte[20],Duration.ofMillis(450)));
                long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertTrue(millis < 1400, "Metadata transfer exceeded hard deadline: " + millis + " ms");
                worker.get(5,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void interruptedThreadDoesNotStartSession() {
        var client=new PeerMetadataSession(new TrackerAddressPolicy(true));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> client.fetch(
                    new InetSocketAddress("127.0.0.1",65534),
                    new byte[20],new byte[20],Duration.ofSeconds(1)));
        } finally {
            Thread.interrupted(); // Restore JUnit worker after deliberately testing interruption.
        }
    }
}
