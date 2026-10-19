package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.dht.DhtLookupResponse;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Full native HTTP Tracker -> TCP Peer -> BEP9 -> verified file list with local sockets. */
class NativeMagnetIntegrationTest {
    @Test
    void magnetToFileListWithoutQbittorrent() throws Exception {
        byte[] info = new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"), new IntegerValue(4),
                ByteString.ascii("name"), new BytesValue("fixture.txt".getBytes(StandardCharsets.UTF_8)),
                ByteString.ascii("piece length"), new IntegerValue(4),
                ByteString.ascii("pieces"), new BytesValue(new byte[20])
        )));
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(info));
        byte[] expectedHash = java.util.HexFormat.of().parseHex(hash);
        byte[] localPeerId = new byte[20];
        Arrays.fill(localPeerId, (byte) 4);
        try (ServerSocket tracker = new ServerSocket(0);
             ServerSocket peer = new ServerSocket(0)) {
            var workers = Executors.newFixedThreadPool(2);
            try {
                var trackerFuture = workers.submit(() -> {
                    try (Socket client = tracker.accept()) {
                        client.setSoTimeout(4000);
                        String request = readHeaders(client);
                        assertTrue(request.startsWith("GET /announce?"));
                        assertTrue(request.contains("info_hash="));
                        int port = peer.getLocalPort();
                        byte[] prefix = "d8:intervali60e5:peers6:"
                                .getBytes(StandardCharsets.US_ASCII);
                        byte[] body = Arrays.copyOf(prefix, prefix.length + 7);
                        System.arraycopy(new byte[]{127,0,0,1,(byte)(port >>> 8),(byte)port,'e'},
                                0,body,prefix.length,7);
                        var output = client.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.flush();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                var peerFuture = workers.submit(() -> {
                    try (Socket client = peer.accept()) {
                        client.setSoTimeout(4000);
                        var input = client.getInputStream();
                        var output = client.getOutputStream();
                        var wire = new PeerWireCodec();
                        byte[] handshake = input.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        assertEquals(PeerWireCodec.HANDSHAKE_LENGTH, handshake.length);
                        assertArrayEquals(expectedHash,
                                wire.decodeHandshake(ByteBuffer.wrap(handshake)).orElseThrow().infoHash());
                        byte[] flags = new byte[8];
                        flags[5] = 0x10;
                        byte[] remotePeerId = new byte[20];
                        Arrays.fill(remotePeerId, (byte) 7);
                        output.write(wire.encodeHandshake(
                                new PeerWireCodec.Handshake(flags,expectedHash,remotePeerId)).array());

                        assertEquals(20,readFrame(input).id()); // client's extension handshake
                        var extensions = new DictionaryValue(Map.of(
                                ByteString.ascii("m"),new DictionaryValue(Map.of(
                                        ByteString.ascii("ut_metadata"),new IntegerValue(3))),
                                ByteString.ascii("metadata_size"),new IntegerValue(info.length)));
                        byte[] ext = new BencodeEncoder().encode(extensions);
                        byte[] extFrame = new byte[ext.length + 1]; // extension message id 0
                        System.arraycopy(ext,0,extFrame,1,ext.length);
                        output.write(wire.encodeMessage(
                                new PeerWireCodec.Message(20,extFrame)).array());
                        output.flush();

                        var request = readFrame(input);
                        assertEquals(20,request.id());
                        assertEquals(3,request.payload()[0] & 255);
                        var header = new DictionaryValue(Map.of(
                                ByteString.ascii("msg_type"), new IntegerValue(1),
                                ByteString.ascii("piece"), new IntegerValue(0),
                                ByteString.ascii("total_size"), new IntegerValue(info.length)));
                        byte[] prefix = new BencodeEncoder().encode(header);
                        byte[] response = new byte[1+prefix.length+info.length];
                        response[0] = 1;
                        System.arraycopy(prefix,0,response,1,prefix.length);
                        System.arraycopy(info,0,response,1+prefix.length,info.length);
                        output.write(wire.encodeMessage(
                                new PeerWireCodec.Message(20,response)).array());
                        output.flush();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });

                String trackerUrl = "http://127.0.0.1:" + tracker.getLocalPort() + "/announce";
                String magnet = "magnet:?xt=urn:btih:" + hash + "&tr="
                        + URLEncoder.encode(trackerUrl,StandardCharsets.UTF_8);
                var result = MetadataResolver.network(new TrackerAddressPolicy(true))
                        .resolve(magnet,localPeerId,6881,List.of());
                assertEquals(hash,result.torrent().infoHash());
                assertEquals("fixture.txt",result.torrent().name());
                assertEquals(1,result.torrent().files().size());
                assertEquals(4,result.torrent().totalBytes());
                assertEquals(1,result.discoveredPeers());
                assertEquals(1,result.attemptedPeers());
                trackerFuture.get(5,TimeUnit.SECONDS);
                peerFuture.get(5,TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
        }
    }

    @Test
    void pureMagnetWithoutTrackerResolvesViaDhtAndBep9ToFileList() throws Exception {
        byte[] info = new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"), new IntegerValue(8),
                ByteString.ascii("name"), new BytesValue("dht_bep9_fixture.txt".getBytes(StandardCharsets.UTF_8)),
                ByteString.ascii("piece length"), new IntegerValue(8),
                ByteString.ascii("pieces"), new BytesValue(new byte[20])
        )));
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(info));
        byte[] expectedHash = java.util.HexFormat.of().parseHex(hash);
        byte[] localPeerId = new byte[20];
        Arrays.fill(localPeerId, (byte) 5);

        try (ServerSocket peer = new ServerSocket(0);
             DatagramSocket udpDht = new DatagramSocket(0)) {
            udpDht.setSoTimeout(4000);
            int peerPort = peer.getLocalPort();
            int dhtPort = udpDht.getLocalPort();

            var workers = Executors.newFixedThreadPool(2);
            try {
                var dhtFuture = workers.submit(() -> {
                    try {
                        byte[] buffer = new byte[1500];
                        var packet = new DatagramPacket(buffer, buffer.length);
                        udpDht.receive(packet);
                        var root = (DictionaryValue) new cc.ivera.magnet.engine.codec.BencodeDecoder().decode(
                                Arrays.copyOf(packet.getData(), packet.getLength()));
                        assertEquals("get_peers", new String(((BytesValue) root.required("q")).bytes(), StandardCharsets.US_ASCII));
                        byte[] transaction = ((BytesValue) root.required("t")).bytes();

                        byte[] compactPeer = new byte[]{127, 0, 0, 1, (byte) (peerPort >>> 8), (byte) peerPort};
                        var fields = new DictionaryValue(Map.of(
                                ByteString.ascii("id"), new BytesValue(new byte[20]),
                                ByteString.ascii("values"), new ListValue(List.of(new BytesValue(compactPeer))),
                                ByteString.ascii("token"), new BytesValue(new byte[]{1, 2, 3})));
                        var reply = new DictionaryValue(Map.of(
                                ByteString.ascii("t"), new BytesValue(transaction),
                                ByteString.ascii("y"), new BytesValue(new byte[]{'r'}),
                                ByteString.ascii("r"), fields));
                        byte[] encoded = new BencodeEncoder().encode(reply);
                        udpDht.send(new DatagramPacket(encoded, encoded.length, packet.getSocketAddress()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                var peerFuture = workers.submit(() -> {
                    try (Socket client = peer.accept()) {
                        client.setSoTimeout(4000);
                        var input = client.getInputStream();
                        var output = client.getOutputStream();
                        var wire = new PeerWireCodec();

                        byte[] handshake = input.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        assertEquals(PeerWireCodec.HANDSHAKE_LENGTH, handshake.length);
                        assertArrayEquals(expectedHash,
                                wire.decodeHandshake(ByteBuffer.wrap(handshake)).orElseThrow().infoHash());

                        byte[] flags = new byte[8];
                        flags[5] = 0x10; // BEP10 extension support
                        byte[] remotePeerId = new byte[20];
                        Arrays.fill(remotePeerId, (byte) 9);
                        output.write(wire.encodeHandshake(
                                new PeerWireCodec.Handshake(flags, expectedHash, remotePeerId)).array());

                        assertEquals(20, readFrame(input).id()); // client extension handshake
                        var extensions = new DictionaryValue(Map.of(
                                ByteString.ascii("m"), new DictionaryValue(Map.of(
                                        ByteString.ascii("ut_metadata"), new IntegerValue(3))),
                                ByteString.ascii("metadata_size"), new IntegerValue(info.length)));
                        byte[] ext = new BencodeEncoder().encode(extensions);
                        byte[] extFrame = new byte[ext.length + 1];
                        System.arraycopy(ext, 0, extFrame, 1, ext.length);
                        output.write(wire.encodeMessage(new PeerWireCodec.Message(20, extFrame)).array());
                        output.flush();

                        var request = readFrame(input);
                        assertEquals(20, request.id());
                        assertEquals(3, request.payload()[0] & 255);

                        var header = new DictionaryValue(Map.of(
                                ByteString.ascii("msg_type"), new IntegerValue(1),
                                ByteString.ascii("piece"), new IntegerValue(0),
                                ByteString.ascii("total_size"), new IntegerValue(info.length)));
                        byte[] prefix = new BencodeEncoder().encode(header);
                        byte[] response = new byte[1 + prefix.length + info.length];
                        response[0] = 1; // client's ut_metadata id
                        System.arraycopy(prefix, 0, response, 1, prefix.length);
                        System.arraycopy(info, 0, response, 1 + prefix.length, info.length);
                        output.write(wire.encodeMessage(new PeerWireCodec.Message(20, response)).array());
                        output.flush();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // Pure magnet URI without ANY &tr= or tracker parameters
                String pureMagnet = "magnet:?xt=urn:btih:" + hash;
                var dhtBootstrapNode = new DhtLookupResponse.Node(
                        new byte[20], new InetSocketAddress("127.0.0.1", dhtPort));

                var result = MetadataResolver.network(new TrackerAddressPolicy(true))
                        .resolve(pureMagnet, localPeerId, 6881, List.of(dhtBootstrapNode));

                assertEquals(hash, result.torrent().infoHash());
                assertEquals("dht_bep9_fixture.txt", result.torrent().name());
                assertEquals(1, result.torrent().files().size());
                assertEquals(8, result.torrent().totalBytes());
                assertEquals(1, result.discoveredPeers());
                assertEquals(1, result.attemptedPeers());
                assertNotNull(result.verifiedTorrentBytes());

                dhtFuture.get(5, TimeUnit.SECONDS);
                peerFuture.get(5, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private static String readHeaders(Socket socket) throws Exception {
        var input = socket.getInputStream();
        var header = new StringBuilder();
        int b;
        while ((b=input.read()) != -1) {
            header.append((char)b);
            if (header.toString().endsWith("\r\n\r\n")) return header.toString();
            if (header.length() > 8192) throw new IllegalStateException("Header too large");
        }
        throw new IllegalStateException("Request did not contain complete headers");
    }
    private static PeerWireCodec.Message readFrame(java.io.InputStream input) throws Exception {
        byte[] prefix=input.readNBytes(4);
        if (prefix.length != 4) throw new IllegalStateException("Missing frame length");
        int length=ByteBuffer.wrap(prefix).getInt();
        if (length < 1 || length > 65536) throw new IllegalStateException("Frame size");
        byte[] body=input.readNBytes(length);
        if (body.length != length) throw new IllegalStateException("Truncated frame");
        ByteBuffer data=ByteBuffer.allocate(length+4).putInt(length).put(body);
        data.flip();
        return new PeerWireCodec().decodeMessage(data).orElseThrow();
    }
}
