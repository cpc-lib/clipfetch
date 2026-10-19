package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded KRPC message serialization; transaction and node IDs remain raw bytes. */
public final class KrpcCodec {
    public static final int MAX_PACKET_BYTES = 1500;
    private final BencodeDecoder decoder = new BencodeDecoder();
    private final BencodeEncoder encoder = new BencodeEncoder();

    public record KrpcMessage(byte[] transaction, char type, String method, DictionaryValue payload) {
        public KrpcMessage {
            transaction = transaction.clone();
            if (transaction.length < 1 || transaction.length > 8)
                throw new IllegalArgumentException("KRPC transaction length");
            if (type != 'q' && type != 'r' && type != 'e') throw new IllegalArgumentException("Invalid KRPC type");
        }
        @Override public byte[] transaction() { return transaction.clone(); }
    }

    public KrpcMessage decode(byte[] packet) {
        if (packet == null || packet.length == 0 || packet.length > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("KRPC packet length");
        Value val = decoder.decode(packet);
        if (!(val instanceof DictionaryValue root)) throw new IllegalArgumentException("KRPC root must be a dictionary");
        byte[] t = bytes(root.required("t"));
        String y = ascii(root.required("y"));
        if (y.length() != 1 || "qre".indexOf(y.charAt(0)) < 0)
            throw new IllegalArgumentException("Unknown KRPC message type");
        char type = y.charAt(0);
        String method = type == 'q' ? ascii(root.required("q")) : null;
        String field = type == 'q' ? "a" : "r";
        Value payloadValue = root.get(field);
        if (type != 'e' && !(payloadValue instanceof DictionaryValue))
            throw new IllegalArgumentException("Missing KRPC payload dictionary");
        return new KrpcMessage(t, type, method,
                payloadValue instanceof DictionaryValue d ? d : new DictionaryValue(Map.of()));
    }

    public byte[] encodeQuery(byte[] transaction, String method, byte[] localNodeId, byte[] target) {
        if (localNodeId.length != 20 || target.length != 20 || method == null
                || (!method.equals("find_node") && !method.equals("get_peers")))
            throw new IllegalArgumentException("Unsupported KRPC query");
        new KrpcMessage(transaction, 'q', method, new DictionaryValue(Map.of()));
        Map<ByteString,Value> a = new LinkedHashMap<>();
        a.put(ByteString.ascii("id"), new BytesValue(localNodeId));
        a.put(ByteString.ascii(method.equals("get_peers") ? "info_hash" : "target"), new BytesValue(target));
        Map<ByteString,Value> root = Map.of(
                ByteString.ascii("t"), new BytesValue(transaction),
                ByteString.ascii("y"), new BytesValue(new byte[] {'q'}),
                ByteString.ascii("q"), new BytesValue(method.getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("a"), new DictionaryValue(a)
        );
        byte[] packet = encoder.encode(new DictionaryValue(root));
        if (packet.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("KRPC packet too large");
        return packet;
    }

    private static byte[] bytes(Value value) {
        if (value instanceof BytesValue b) return b.bytes();
        throw new IllegalArgumentException("Expected KRPC bytes");
    }
    private static String ascii(Value value) {
        byte[] raw = bytes(value);
        for (byte b : raw) if ((b & 255) > 127) throw new IllegalArgumentException("Non-ASCII KRPC value");
        return new String(raw, StandardCharsets.US_ASCII);
    }
}
