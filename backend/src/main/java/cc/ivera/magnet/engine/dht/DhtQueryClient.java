package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.Arrays;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One-shot BEP5 query transport (find_node/get_peers). Not a DHT crawler.
 * Endpoints are validated and pinned; unsolicited/mismatched transactions ignored.
 */
public final class DhtQueryClient {
    private final TrackerAddressPolicy policy;
    private final KrpcCodec codec = new KrpcCodec();
    public DhtQueryClient(TrackerAddressPolicy policy) { this.policy = policy; }

    public DhtLookupResponse query(InetSocketAddress endpoint, byte[] localId, byte[] target,
                                    String method, Duration timeout) throws IOException {
        if (endpoint == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid query endpoint or timeout");
        timeout=NativeNetworkBudget.cap(timeout);
        var address = policy.resolve(endpoint.getHostString());
        var pinned = new InetSocketAddress(address,endpoint.getPort());
        byte[] transaction = new byte[2];
        ThreadLocalRandom.current().nextBytes(transaction);
        byte[] request = codec.encodeQuery(transaction,method,localId,target);
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(pinned);
            channel.configureBlocking(false);
            channel.write(ByteBuffer.wrap(request));
            ByteBuffer input = ByteBuffer.allocate(KrpcCodec.MAX_PACKET_BYTES);
            long deadline = System.nanoTime()+timeout.toNanos();
            while (System.nanoTime() < deadline) {
                input.clear();
                int n = channel.read(input);
                if (n > 0) {
                    input.flip();
                    byte[] data = new byte[n]; input.get(data);
                    var reply = codec.decode(data);
                    if (!Arrays.equals(reply.transaction(),transaction)) continue;
                    if (reply.type() != 'r') throw new IOException("DHT query rejected");
                    var parsed = DhtLookupResponse.parse(reply);
                    for (var peer : parsed.peers()) {
                        if (!policy.isAllowed(policy.resolve(peer.getHostString())))
                            throw new SecurityException("Restricted DHT peer");
                    }
                    for (var node : parsed.nodes()) policy.resolve(node.address().getHostString());
                    return parsed;
                }
                try { Thread.sleep(10); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IOException("DHT query interrupted",e);
                }
            }
            throw new IOException("DHT query timeout");
        }
    }
}
