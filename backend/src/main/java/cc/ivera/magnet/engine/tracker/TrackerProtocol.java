package cc.ivera.magnet.engine.tracker;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Pure parsers/builders for HTTP & UDP Tracker (BEP 3 / BEP 15). */
public final class TrackerProtocol {
    private TrackerProtocol() {}
    public static String percentEncode(byte[] value) {
        StringBuilder b = new StringBuilder(value.length * 3);
        for (byte v : value) b.append('%').append(HexFormat.of().withUpperCase().toHexDigits(v));
        return b.toString();
    }
    public static List<InetSocketAddress> compactIpv4(byte[] raw) {
        if (raw.length % 6 != 0 || raw.length > 6 * 10000)
            throw new IllegalArgumentException("Invalid compact peers length");
        List<InetSocketAddress> peers = new ArrayList<>();
        for (int i = 0; i < raw.length; i += 6) {
            int port = ((raw[i + 4] & 255) << 8) | (raw[i + 5] & 255);
            if (port == 0) continue;
            String ip = (raw[i] & 255) + "." + (raw[i + 1] & 255) + "." +
                    (raw[i + 2] & 255) + "." + (raw[i + 3] & 255);
            peers.add(InetSocketAddress.createUnresolved(ip, port));
        }
        return List.copyOf(peers);
    }
    public static AnnounceResponse parseHttpResponse(byte[] raw) {
        if (raw.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Tracker response too large");
        Value decoded = new BencodeDecoder().decode(raw);
        if (!(decoded instanceof DictionaryValue d)) throw new IllegalArgumentException("Invalid tracker reply");
        if (d.get("failure reason") instanceof BytesValue failure)
            throw new IllegalArgumentException("Tracker failure: " + new String(failure.bytes(), StandardCharsets.UTF_8));
        long interval = integer(d.get("interval"), 1800);
        if (interval < 1 || interval > 86400) throw new IllegalArgumentException("Invalid tracker interval");
        Value peers = d.get("peers");
        if (!(peers instanceof BytesValue b)) throw new IllegalArgumentException("Noncompact peers not yet supported");
        return new AnnounceResponse(interval, compactIpv4(b.bytes()));
    }
    private static long integer(Value val, long fallback) {
        if (val == null) return fallback;
        if (val instanceof IntegerValue i) return i.value();
        throw new IllegalArgumentException("Invalid integer field");
    }
    public static byte[] udpConnectRequest(int transactionId) {
        return ByteBuffer.allocate(16).putLong(0x41727101980L).putInt(0).putInt(transactionId).array();
    }
    public static long parseUdpConnectResponse(byte[] data, int transactionId) {
        if (data.length != 16) throw new IllegalArgumentException("Invalid UDP connect response length");
        ByteBuffer b = ByteBuffer.wrap(data);
        int action = b.getInt(), txn = b.getInt();
        if (action != 0 || txn != transactionId) throw new IllegalArgumentException("UDP tracker transaction mismatch");
        return b.getLong();
    }
    public static byte[] udpAnnounceRequest(long connectionId, int transactionId, byte[] infoHash,
                                            byte[] peerId, long downloaded, long left, long uploaded, int port) {
        if (infoHash.length != 20 || peerId.length != 20 || port < 1 || port > 65535
                || downloaded < 0 || left < 0 || uploaded < 0)
            throw new IllegalArgumentException("Invalid UDP announce parameters");
        return ByteBuffer.allocate(98).putLong(connectionId).putInt(1).putInt(transactionId)
                .put(infoHash).put(peerId).putLong(downloaded).putLong(left).putLong(uploaded)
                .putInt(0).putInt(0).putInt(0).putInt(-1).putShort((short) port).array();
    }
    public static AnnounceResponse parseUdpAnnounceResponse(byte[] data, int transactionId) {
        if (data.length < 20 || data.length > 20 + 6 * 10000) throw new IllegalArgumentException("Invalid UDP announce response");
        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.getInt() != 1 || b.getInt() != transactionId) throw new IllegalArgumentException("UDP announce transaction mismatch");
        long interval = Integer.toUnsignedLong(b.getInt());
        b.getInt(); b.getInt(); // leechers / seeders
        if (interval < 1 || interval > 86400) throw new IllegalArgumentException("Invalid UDP interval");
        byte[] peers = new byte[b.remaining()];
        b.get(peers);
        return new AnnounceResponse(interval, compactIpv4(peers));
    }
    public record AnnounceResponse(long intervalSeconds, List<InetSocketAddress> peers) {
        public AnnounceResponse { peers = List.copyOf(peers); }
    }
}
