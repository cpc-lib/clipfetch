package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DhtUdpClientTest {
    @Test void loopbackDhtPingValidatesTransactionAndNodeId() throws Exception {
        try (var socket = new DatagramSocket(0)) {
            socket.setSoTimeout(5000);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var fixture = pool.submit(() -> {
                    try {
                        var req = new DatagramPacket(new byte[1500],1500);
                        socket.receive(req);
                        var root = (DictionaryValue) new BencodeDecoder().decode(
                                Arrays.copyOf(req.getData(),req.getLength()));
                        byte[] tx = ((BytesValue)root.required("t")).bytes();
                        var reply = new DictionaryValue(Map.of(
                                ByteString.ascii("t"),new BytesValue(tx),
                                ByteString.ascii("y"),new BytesValue(new byte[]{'r'}),
                                ByteString.ascii("r"),new DictionaryValue(Map.of(
                                        ByteString.ascii("id"),new BytesValue(new byte[20])))));
                        byte[] response = new BencodeEncoder().encode(reply);
                        socket.send(new DatagramPacket(response,response.length,req.getSocketAddress()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                byte[] remoteId = new DhtUdpClient(new TrackerAddressPolicy(true)).ping(
                        new InetSocketAddress("127.0.0.1",socket.getLocalPort()),new byte[20],Duration.ofSeconds(3));
                assertArrayEquals(new byte[20],remoteId);
                fixture.get(5,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
}
