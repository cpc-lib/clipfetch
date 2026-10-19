package cc.ivera.magnet.engine.peer;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import java.net.InetSocketAddress;
import java.util.*;

/** Bounded peer candidate collection with source attribution. */
public final class PeerCandidatePool {
    public enum Source { HTTP_TRACKER, UDP_TRACKER, DHT, PEX, LSD, PUBLIC_TRACKER }
    public record Candidate(InetSocketAddress address, Set<Source> sources) {
        public Candidate { sources = Set.copyOf(sources); }
    }
    private final int limit;
    private final TrackerAddressPolicy policy;
    private final LinkedHashMap<String, InetSocketAddress> addresses = new LinkedHashMap<>();
    private final Map<String, EnumSet<Source>> sources = new HashMap<>();

    public PeerCandidatePool(int limit, TrackerAddressPolicy policy) {
        if (limit < 1 || limit > 10000 || policy == null) throw new IllegalArgumentException("Invalid candidate pool");
        this.limit = limit;
        this.policy = policy;
    }

    public synchronized boolean offer(InetSocketAddress address, Source source) {
        if (address == null || source == null || address.getPort() < 1 || address.getPort() > 65535) return false;
        try { policy.resolve(address.getHostString()); }
        catch (Exception failure) { return false; }
        String key = address.getHostString().toLowerCase(Locale.ROOT) + ":" + address.getPort();
        if (!addresses.containsKey(key) && addresses.size() >= limit) return false;
        addresses.putIfAbsent(key, address);
        sources.computeIfAbsent(key, unused -> EnumSet.noneOf(Source.class)).add(source);
        return true;
    }

    /** Local discovery bypasses the PUBLIC address policy only for an IPv4 private LAN Peer. */
    public synchronized boolean offerLan(InetSocketAddress address) {
        if (address == null || address.getPort() < 1 || address.getPort() > 65535
                || !(address.getAddress() instanceof java.net.Inet4Address ip)
                || !ip.isSiteLocalAddress()) return false;
        String key = ip.getHostAddress() + ":" + address.getPort();
        if (!addresses.containsKey(key) && addresses.size() >= limit) return false;
        addresses.putIfAbsent(key,address);
        sources.computeIfAbsent(key,unused -> EnumSet.noneOf(Source.class)).add(Source.LSD);
        return true;
    }
    public synchronized List<Candidate> snapshot() {
        List<Candidate> result = new ArrayList<>();
        for (var entry : addresses.entrySet()) {
            result.add(new Candidate(entry.getValue(), sources.get(entry.getKey())));
        }
        return List.copyOf(result);
    }
    public synchronized int size() { return addresses.size(); }
}
