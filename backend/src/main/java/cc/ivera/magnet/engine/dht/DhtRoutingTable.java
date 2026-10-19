package cc.ivera.magnet.engine.dht;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** A bounded distance-bucket routing table; network liveness/eviction is a later step. */
public final class DhtRoutingTable {
    private static final int HASH_BYTES = 20;
    private static final int BUCKET_CAPACITY = 8;
    private final byte[] localId;
    private final List<List<Node>> buckets = new ArrayList<>(160);

    public DhtRoutingTable(byte[] localId) {
        requireId(localId);
        this.localId = localId.clone();
        for (int i = 0; i < 160; i++) buckets.add(new ArrayList<>());
    }
    public synchronized boolean add(byte[] nodeId, InetSocketAddress address) {
        requireId(nodeId);
        if (Arrays.equals(localId, nodeId)) return false;
        if (address == null || address.getPort() < 1 || address.getPort() > 65535)
            throw new IllegalArgumentException("Invalid DHT node address");
        int index = bucketIndex(localId, nodeId);
        List<Node> bucket = buckets.get(index);
        Node node = new Node(nodeId, address);
        if (bucket.stream().anyMatch(n -> Arrays.equals(n.id, node.id))) return false;
        if (bucket.size() == BUCKET_CAPACITY) return false;
        bucket.add(node);
        return true;
    }
    public synchronized List<Node> closest(byte[] target, int count) {
        requireId(target);
        if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid node count");
        return buckets.stream().flatMap(List::stream)
                .sorted(Comparator.comparing(n -> xorDistance(n.id, target)))
                .limit(count).toList();
    }
    public synchronized int size() { return buckets.stream().mapToInt(List::size).sum(); }
    public static int bucketIndex(byte[] left, byte[] right) {
        requireId(left); requireId(right);
        byte[] xor = new byte[HASH_BYTES];
        for (int i = 0; i < HASH_BYTES; i++) xor[i] = (byte) (left[i] ^ right[i]);
        BigInteger distance = new BigInteger(1, xor);
        return distance.signum() == 0 ? -1 : distance.bitLength() - 1;
    }
    public static BigInteger xorDistance(byte[] left, byte[] right) {
        requireId(left); requireId(right);
        byte[] xor = new byte[HASH_BYTES];
        for (int i = 0; i < HASH_BYTES; i++) xor[i] = (byte) (left[i] ^ right[i]);
        return new BigInteger(1, xor);
    }
    private static void requireId(byte[] id) {
        if (id == null || id.length != HASH_BYTES) throw new IllegalArgumentException("DHT node ID must be 20 bytes");
    }
    public record Node(byte[] id, InetSocketAddress address) {
        public Node { id = id.clone(); }
        @Override public byte[] id() { return id.clone(); }
    }
}
