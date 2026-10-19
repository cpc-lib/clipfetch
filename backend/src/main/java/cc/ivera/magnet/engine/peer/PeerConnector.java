package cc.ivera.magnet.engine.peer;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;

/** One-shot BEP3 handshake with strict torrent identity verification. */
public final class PeerConnector {
    private final TrackerAddressPolicy policy;
    private final PeerWireCodec codec = new PeerWireCodec();

    public PeerConnector(TrackerAddressPolicy policy) { this.policy = policy; }

    public PeerWireCodec.Handshake handshake(InetSocketAddress endpoint,
            byte[] infoHash, byte[] localPeerId, Duration timeout) throws IOException {
        if (endpoint == null || infoHash == null || infoHash.length != 20
                || localPeerId == null || localPeerId.length != 20
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid Peer handshake arguments");
        }
        var pinned = new InetSocketAddress(policy.resolve(endpoint.getHostString()), endpoint.getPort());
        int timeoutMs = (int)Math.max(1, timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(pinned, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            byte[] reserved = new byte[8];
            reserved[5] = 0x10;
            var local = new PeerWireCodec.Handshake(reserved, infoHash, localPeerId);
            socket.getOutputStream().write(codec.encodeHandshake(local).array());
            socket.getOutputStream().flush();
            byte[] received = socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
            if (received.length != PeerWireCodec.HANDSHAKE_LENGTH)
                throw new IOException("Incomplete Peer handshake");
            PeerWireCodec.Handshake remote;
            try {
                remote = codec.decodeHandshake(ByteBuffer.wrap(received)).orElseThrow();
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed remote handshake", e);
            }
            if (!Arrays.equals(remote.infoHash(), infoHash))
                throw new IOException("Peer InfoHash mismatch");
            if (Arrays.equals(remote.peerId(), localPeerId))
                throw new IOException("Peer ID collision");
            return remote;
        }
    }
}
