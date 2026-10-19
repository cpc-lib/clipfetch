package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/**
 * Bounded, synchronous BEP5 peer lookup. No background threads and no downloads.
 * Queries nearest unvisited nodes, stopping at query/time/peer limits.
 */
public final class IterativePeerLookup {
    @FunctionalInterface
    public interface QueryTransport {
        DhtLookupResponse getPeers(InetSocketAddress address, byte[] localId,
                                   byte[] infoHash, Duration timeout) throws IOException;
    }
    public record Result(List<InetSocketAddress> peers, int queriedNodes, int discoveredNodes) {
        public Result { peers = List.copyOf(peers); }
    }
    private record Candidate(byte[] id, InetSocketAddress address) {
        Candidate { id = id.clone(); }
        @Override public byte[] id() { return id.clone(); }
    }
    private final QueryTransport transport;
    private final TrackerAddressPolicy policy;
    private final int maxQueries, maxPeers, maxCandidates;
    private final Duration totalTimeout, perQueryTimeout;

    public IterativePeerLookup(QueryTransport transport, TrackerAddressPolicy policy,
                               int maxQueries, int maxPeers, int maxCandidates,
                               Duration totalTimeout, Duration perQueryTimeout) {
        if (transport == null || policy == null || maxQueries < 1 || maxQueries > 512
                || maxPeers < 1 || maxPeers > 10000 || maxCandidates < 1 || maxCandidates > 4096
                || totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()
                || perQueryTimeout == null || perQueryTimeout.isZero() || perQueryTimeout.isNegative())
            throw new IllegalArgumentException("Invalid lookup limits");
        this.transport=transport; this.policy=policy; this.maxQueries=maxQueries;
        this.maxPeers=maxPeers; this.maxCandidates=maxCandidates;
        this.totalTimeout=totalTimeout; this.perQueryTimeout=perQueryTimeout;
    }
    public static IterativePeerLookup network(TrackerAddressPolicy policy) {
        DhtQueryClient client = new DhtQueryClient(policy);
        return new IterativePeerLookup((endpoint,id,hash,timeout) ->
                client.query(endpoint,id,hash,"get_peers",timeout),
                policy,64,256,1024,Duration.ofSeconds(60),Duration.ofSeconds(3));
    }
    public Result lookup(byte[] localId, byte[] infoHash, List<DhtLookupResponse.Node> bootstrap) {
        if (localId == null || localId.length != 20 || infoHash == null || infoHash.length != 20
                || bootstrap == null) throw new IllegalArgumentException("Invalid lookup identifiers");
        byte[] local = localId.clone(), target = infoHash.clone();
        Map<String,Candidate> candidates = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Map<String,InetSocketAddress> peers = new LinkedHashMap<>();
        for (var n:bootstrap) addCandidate(n,candidates);
        Duration effective=totalTimeout;
        try { effective=NativeNetworkBudget.cap(totalTimeout); }
        catch(IOException exhausted) {return new Result(List.of(),0,candidates.size());}
        long deadline = System.nanoTime()+effective.toNanos();
        int queried=0;
        while (queried < maxQueries && peers.size() < maxPeers && System.nanoTime() < deadline
                && !Thread.currentThread().isInterrupted()) {
            Candidate next = candidates.entrySet().stream()
                    .filter(e -> !visited.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .min(Comparator.comparing(n -> DhtRoutingTable.xorDistance(n.id,target)))
                    .orElse(null);
            if (next == null) break;
            String key = key(next.address);
            visited.add(key); queried++;
            try {
                if (!allowed(next.address)) continue;
                long available = deadline-System.nanoTime();
                if (available <= 0) break;
                Duration timeout = NativeNetworkBudget.cap(
                        Duration.ofNanos(Math.min(available,perQueryTimeout.toNanos())));
                DhtLookupResponse answer = transport.getPeers(next.address,local,target,timeout);
                for (var n : answer.nodes()) addCandidate(n,candidates);
                for (var peer : answer.peers()) {
                    if (peers.size() >= maxPeers) break;
                    if (allowed(peer)) peers.putIfAbsent(key(peer),peer);
                }
            } catch (IOException | SecurityException | IllegalArgumentException ignored) {
                // Other peers can still provide answers; no retry storms in this stage.
            }
        }
        return new Result(List.copyOf(peers.values()),queried,candidates.size());
    }
    private void addCandidate(DhtLookupResponse.Node node, Map<String,Candidate> candidates) {
        if (node == null || candidates.size() >= maxCandidates || !allowed(node.address())) return;
        String key = key(node.address());
        candidates.putIfAbsent(key,new Candidate(node.id(),node.address()));
    }
    private boolean allowed(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535) return false;
        try { return policy.isAllowed(policy.resolve(endpoint.getHostString())); }
        catch (Exception e) { return false; }
    }
    private static String key(InetSocketAddress address) {
        return address.getHostString().toLowerCase(Locale.ROOT)+":"+address.getPort();
    }
}
