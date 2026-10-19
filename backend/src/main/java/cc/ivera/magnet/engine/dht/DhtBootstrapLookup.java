package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/**
 * Bounded DHT bootstrap for magnet URIs without tr= parameters.
 * Bootstrap routers only supply initial nodes, never trusted metadata.
 */
public final class DhtBootstrapLookup {
    @FunctionalInterface
    public interface BootstrapTransport {
        DhtLookupResponse query(InetSocketAddress router, byte[] localNodeId,
                                byte[] infoHash, Duration timeout) throws IOException;
    }
    @FunctionalInterface
    public interface IterativeTransport {
        List<InetSocketAddress> find(byte[] localNodeId, byte[] infoHash,
                                     List<DhtLookupResponse.Node> seeds);
    }

    public record Result(List<InetSocketAddress> peers, int respondingRouters,
                         int seedNodes) {
        public Result { peers = List.copyOf(peers); }
    }

    private final List<InetSocketAddress> routers;
    private final BootstrapTransport bootstrapTransport;
    private final IterativeTransport iterativeTransport;
    private final int maxPeers;
    private final Duration bootstrapTimeout;

    public DhtBootstrapLookup(List<InetSocketAddress> routers,
                              BootstrapTransport bootstrapTransport,
                              IterativeTransport iterativeTransport,
                              int maxPeers, Duration bootstrapTimeout) {
        if (routers == null || routers.isEmpty() || routers.size() > 16
                || bootstrapTransport == null || iterativeTransport == null
                || maxPeers < 1 || maxPeers > 10000 || bootstrapTimeout == null
                || bootstrapTimeout.isNegative() || bootstrapTimeout.isZero()
                || bootstrapTimeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid DHT Bootstrap settings");
        this.routers = List.copyOf(routers);
        this.bootstrapTransport = bootstrapTransport;
        this.iterativeTransport = iterativeTransport;
        this.maxPeers = maxPeers;
        this.bootstrapTimeout = bootstrapTimeout;
    }

    public static DhtBootstrapLookup publicNetwork(TrackerAddressPolicy policy) {
        DhtQueryClient queryClient = new DhtQueryClient(policy);
        IterativePeerLookup iterative = new IterativePeerLookup(
                (endpoint, id, hash, timeout) ->
                        queryClient.query(endpoint, id, hash, "get_peers", timeout),
                policy, 32, 256, 512, Duration.ofSeconds(24), Duration.ofSeconds(2));
        return new DhtBootstrapLookup(
                List.of(
                        InetSocketAddress.createUnresolved("dht.transmissionbt.com", 6881),
                        InetSocketAddress.createUnresolved("router.utorrent.com", 6881),
                        InetSocketAddress.createUnresolved("router.bittorrent.com", 6881)),
                (endpoint, id, hash, timeout) ->
                        queryClient.query(endpoint, id, hash, "get_peers", timeout),
                (id, hash, nodes) -> iterative.lookup(id, hash, nodes).peers(),
                256, Duration.ofSeconds(3));
    }

    public Result find(byte[] localNodeId, byte[] infoHash) {
        if (localNodeId == null || localNodeId.length != 20
                || infoHash == null || infoHash.length != 20)
            throw new IllegalArgumentException("DHT IDs must be 20 bytes");

        Map<String, DhtLookupResponse.Node> seeds = new LinkedHashMap<>();
        Map<String, InetSocketAddress> peers = new LinkedHashMap<>();
        int responding = 0;

        for (InetSocketAddress router : routers) {
            if (Thread.currentThread().isInterrupted() || NativeNetworkBudget.exhausted()) break;
            try {
                DhtLookupResponse reply = bootstrapTransport.query(
                        router, localNodeId.clone(), infoHash.clone(),
                        NativeNetworkBudget.cap(bootstrapTimeout));
                responding++;
                seeds.putIfAbsent(key(router),
                        new DhtLookupResponse.Node(reply.responderId(), router));
                for (DhtLookupResponse.Node node : reply.nodes()) {
                    if (seeds.size() >= 512) break;
                    seeds.putIfAbsent(key(node.address()), node);
                }
                for (InetSocketAddress address : reply.peers()) {
                    if (peers.size() >= maxPeers) break;
                    peers.putIfAbsent(key(address), address);
                }
            } catch (IOException | SecurityException | IllegalArgumentException ignored) {
                // Another independently configured bootstrap router may answer.
            }
        }

        int seedCount = seeds.size();
        if (!seeds.isEmpty() && peers.size() < maxPeers
                && !Thread.currentThread().isInterrupted() &&
                !NativeNetworkBudget.exhausted()) {
            for (InetSocketAddress address : iterativeTransport.find(
                    localNodeId.clone(), infoHash.clone(), new ArrayList<>(seeds.values()))) {
                if (peers.size() >= maxPeers) break;
                peers.putIfAbsent(key(address), address);
            }
        }
        return new Result(List.copyOf(peers.values()), responding, seedCount);
    }

    private static String key(InetSocketAddress address) {
        return address.getHostString().toLowerCase(java.util.Locale.ROOT)
                + ":" + address.getPort();
    }
}
