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

class DhtQueryClientTest {
    @Test void getPeersQueryReceivesValidatedCompactPeersAndNodes() throws Exception {
        try (DatagramSocket udp = new DatagramSocket(0)) {
            udp.setSoTimeout(4000);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var future = pool.submit(() -> {
                    try {
                        var request = new DatagramPacket(new byte[1500], 1500);
                        udp.receive(request);
                        var root = (DictionaryValue)new BencodeDecoder().decode(
                                Arrays.copyOf(request.getData(),request.getLength()));
                        assertArrayEquals("get_peers".getBytes(),((BytesValue)root.required("q")).bytes());
                        byte[] transaction = ((BytesValue)root.required("t")).bytes();
                        byte[] nodes = new byte[26]; nodes[20] = 8; nodes[21] = 8; nodes[22] = 8; nodes[23] = 8;
                        nodes[24] = 26; nodes[25] = (byte)225;
                        var fields = new DictionaryValue(Map.of(
                                ByteString.ascii("id"),new BytesValue(new byte[20]),
                                ByteString.ascii("nodes"),new BytesValue(nodes),
                                ByteString.ascii("values"),new ListValue(java.util.List.of(
                                        new BytesValue(new byte[]{1,1,1,1,26,(byte)225}))),
                                ByteString.ascii("token"),new BytesValue(new byte[]{1,2,3})));
                        var reply = new DictionaryValue(Map.of(
                                ByteString.ascii("t"),new BytesValue(transaction),
                                ByteString.ascii("y"),new BytesValue(new byte[]{'r'}),
                                ByteString.ascii("r"),fields));
                        byte[] encoded = new BencodeEncoder().encode(reply);
                        udp.send(new DatagramPacket(encoded,encoded.length,request.getSocketAddress()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                var response = new DhtQueryClient(new TrackerAddressPolicy(true)).query(
                        new InetSocketAddress("127.0.0.1",udp.getLocalPort()),
                        new byte[20],new byte[20],"get_peers",Duration.ofSeconds(3));
                assertEquals(1,response.nodes().size());
                assertEquals(1,response.peers().size());
                assertEquals(6881,response.peers().getFirst().getPort());
                assertArrayEquals(new byte[]{1,2,3},response.token());
                future.get(4, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void rejectsMalformedCompactNodes() {
        var packet = new KrpcCodec.KrpcMessage(new byte[]{1},'r',null,
                new DictionaryValue(Map.of(
                        ByteString.ascii("id"),new BytesValue(new byte[20]),
                        ByteString.ascii("nodes"),new BytesValue(new byte[25]))));
        assertThrows(IllegalArgumentException.class,() -> DhtLookupResponse.parse(packet));
    }
}
