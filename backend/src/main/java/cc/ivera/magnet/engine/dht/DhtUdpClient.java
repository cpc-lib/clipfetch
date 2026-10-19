package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Minimal UDP DHT ping, no iterative peer lookup or routing lifecycle yet. */
public final class DhtUdpClient {
    private final TrackerAddressPolicy addressPolicy;
    private final KrpcCodec codec = new KrpcCodec();
    private final BencodeEncoder encoder = new BencodeEncoder();

    public DhtUdpClient(TrackerAddressPolicy addressPolicy) { this.addressPolicy = addressPolicy; }

    public byte[] ping(InetSocketAddress endpoint, byte[] nodeId, Duration timeout) throws IOException {
        if (nodeId == null || nodeId.length != 20 || endpoint == null || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid DHT ping arguments");
        var resolved = addressPolicy.resolve(endpoint.getHostString());
        var pinned = new InetSocketAddress(resolved, endpoint.getPort());
        byte[] transaction = new byte[2];
        ThreadLocalRandom.current().nextBytes(transaction);
        var query = new DictionaryValue(Map.of(
                ByteString.ascii("t"), new BytesValue(transaction),
                ByteString.ascii("y"), new BytesValue(new byte[]{'q'}),
                ByteString.ascii("q"), new BytesValue(new byte[]{'p','i','n','g'}),
                ByteString.ascii("a"), new DictionaryValue(Map.of(ByteString.ascii("id"),new BytesValue(nodeId)))));
        byte[] request = encoder.encode(query);
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(pinned);
            channel.configureBlocking(false);
            channel.write(ByteBuffer.wrap(request));
            ByteBuffer input = ByteBuffer.allocate(KrpcCodec.MAX_PACKET_BYTES);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                input.clear();
                int length = channel.read(input);
                if (length > 0) {
                    input.flip();
                    byte[] response = new byte[length]; input.get(response);
                    var message = codec.decode(response);
                    if (!java.util.Arrays.equals(message.transaction(), transaction)) continue;
                    if (message.type() != 'r') throw new IOException("DHT ping rejected");
                    var remote = message.payload().get("id");
                    if (!(remote instanceof BytesValue b) || b.bytes().length != 20)
                        throw new IOException("DHT reply contains no valid node ID");
                    return b.bytes();
                }
                try { Thread.sleep(10); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("DHT ping interrupted", e);
                }
            }
            throw new IOException("DHT ping timeout");
        }
    }
}
