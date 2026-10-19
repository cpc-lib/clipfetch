package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.tracker.TrackerProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Validated BEP5 get_peers / find_node response, IPv4 subset. */
public record DhtLookupResponse(byte[] responderId, List<Node> nodes,
                                List<InetSocketAddress> peers, byte[] token) {
    public DhtLookupResponse {
        responderId = responderId.clone();
        nodes = List.copyOf(nodes);
        peers = List.copyOf(peers);
        token = token == null ? null : token.clone();
    }
    @Override public byte[] responderId() { return responderId.clone(); }
    @Override public byte[] token() { return token == null ? null : token.clone(); }
    public record Node(byte[] id, InetSocketAddress address) {
        public Node { id = id.clone(); }
        @Override public byte[] id() { return id.clone(); }
    }
    public static DhtLookupResponse parse(KrpcCodec.KrpcMessage packet) {
        if (packet.type() != 'r') throw new IllegalArgumentException("Expected KRPC response");
        DictionaryValue d = packet.payload();
        byte[] id = requiredBytes(d, "id");
        if (id.length != 20) throw new IllegalArgumentException("Invalid responder node ID");
        List<Node> nodes = new ArrayList<>();
        if (d.get("nodes") != null) {
            byte[] compact = requiredBytes(d, "nodes");
            if (compact.length % 26 != 0 || compact.length > 26 * 100)
                throw new IllegalArgumentException("Invalid DHT nodes field");
            for (int pos=0;pos<compact.length;pos+=26) {
                byte[] nodeId = java.util.Arrays.copyOfRange(compact,pos,pos+20);
                byte[] peer = java.util.Arrays.copyOfRange(compact,pos+20,pos+26);
                var addresses = TrackerProtocol.compactIpv4(peer);
                if (!addresses.isEmpty()) nodes.add(new Node(nodeId,addresses.getFirst()));
            }
        }
        List<InetSocketAddress> peers = new ArrayList<>();
        if (d.get("values") != null) {
            if (!(d.get("values") instanceof ListValue list) || list.values().size() > 100)
                throw new IllegalArgumentException("Invalid DHT values");
            for (Value val : list.values()) {
                if (!(val instanceof BytesValue binary) || binary.bytes().length != 6)
                    throw new IllegalArgumentException("Invalid compact DHT peer");
                peers.addAll(TrackerProtocol.compactIpv4(binary.bytes()));
            }
        }
        byte[] token = d.get("token") == null ? null : requiredBytes(d,"token");
        if (token != null && token.length > 128) throw new IllegalArgumentException("Oversized DHT token");
        return new DhtLookupResponse(id,nodes,peers,token);
    }
    private static byte[] requiredBytes(DictionaryValue dict, String key) {
        if (!(dict.required(key) instanceof BytesValue bytes))
            throw new IllegalArgumentException("Invalid DHT field " + key);
        return bytes.bytes();
    }
}
