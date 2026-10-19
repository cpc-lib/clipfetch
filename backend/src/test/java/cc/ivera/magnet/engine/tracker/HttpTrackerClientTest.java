package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpTrackerClientTest {
    @Test void loopbackFixtureVerifiesRequestAndCompactPeers() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var fixture = executor.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        var input = socket.getInputStream();
                        var text = new StringBuilder();
                        int previous = 0, value;
                        while ((value = input.read()) != -1) {
                            text.append((char) value);
                            if (text.toString().endsWith("\r\n\r\n")) break;
                            if (text.length() > 8192) throw new IllegalStateException("Headers too long");
                            previous = value;
                        }
                        String request = text.toString();
                        if (!request.startsWith("GET /announce?")) throw new AssertionError(request);
                        if (!request.contains("info_hash=%00%00%00")) throw new AssertionError("Wrong info_hash");
                        byte[] bencode = "d8:intervali1800e5:peers6:".getBytes(StandardCharsets.US_ASCII);
                        byte[] body = new byte[bencode.length + 7];
                        System.arraycopy(bencode,0,body,0,bencode.length);
                        System.arraycopy(new byte[]{8,8,8,8,0x1a,(byte)0xe1,'e'},0,body,bencode.length,7);
                        var output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.flush();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                var client = new HttpTrackerClient(new TrackerAddressPolicy(true));
                var response = client.announce(URI.create("http://127.0.0.1:"+server.getLocalPort()+"/announce"),
                        new byte[20],new byte[20],0,100,0,6881,Duration.ofSeconds(3));
                assertEquals(1800,response.intervalSeconds());
                assertEquals(6881,response.peers().getFirst().getPort());
                fixture.get(5, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void optInOfficialUbuntuHttpsTrackerAcceptance() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("native.tracker.internet"),"Real public Tracker test is opt-in");
        var client=new HttpTrackerClient(new TrackerAddressPolicy(false));
        byte[] hash=java.util.HexFormat.of().parseHex(
                "dafc8c076ca2f3ed376eeae7c76a0d6be2415c45");
        byte[] peerId="-CF0001-TEST12345678".getBytes(StandardCharsets.US_ASCII);
        var response=client.announce(URI.create("https://torrent.ubuntu.com/announce"),
                hash,peerId,0,6518974464L,0,6881,Duration.ofSeconds(15));
        assertTrue(response.intervalSeconds()>0);
        System.out.println("NATIVE_HTTPS_TRACKER peers="+response.peers().size());
    }

    @Test void securityPolicyBlocksLoopbackForNormalMode() {
        var client = new HttpTrackerClient(new TrackerAddressPolicy(false));
        assertThrows(SecurityException.class, () -> client.announce(
                URI.create("http://127.0.0.1:9000/announce"),new byte[20],new byte[20],
                0,1,0,6881,Duration.ofSeconds(1)));
    }
    @Test void httpsUsesTlsAndNeverFallsBackToPlaintext() throws Exception {
        try(var server=new ServerSocket(0)) {
            var pool=Executors.newSingleThreadExecutor();
            try {
                var traffic=pool.submit(()->{
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(2000);
                        int first=socket.getInputStream().read();
                        // TLS Handshake records start with 0x16, never 'G' from GET.
                        return first;
                    }
                });
                var client=new HttpTrackerClient(new TrackerAddressPolicy(true));
                assertThrows(java.io.IOException.class,()->client.announce(
                        URI.create("https://127.0.0.1:"+server.getLocalPort()+"/announce"),
                        new byte[20],new byte[20],0,1,0,6881,Duration.ofMillis(800)));
                assertEquals(0x16,traffic.get(3,TimeUnit.SECONDS));
            }finally {pool.shutdownNow();}
        }
    }
}
