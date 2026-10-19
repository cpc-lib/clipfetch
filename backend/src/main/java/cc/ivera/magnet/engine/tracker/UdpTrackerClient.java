package cc.ivera.magnet.engine.tracker;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/** BEP15 connection and announce over DatagramChannel; no background threads. */
public final class UdpTrackerClient {
    private final TrackerAddressPolicy policy;
    public UdpTrackerClient(TrackerAddressPolicy policy) { this.policy = policy; }

    public TrackerProtocol.AnnounceResponse announce(
            URI tracker, byte[] infoHash, byte[] peerId, long downloaded,
            long left, long uploaded, int listenPort) throws IOException {
        return announce(tracker,infoHash,peerId,downloaded,left,uploaded,listenPort,
                Duration.ofSeconds(30));
    }

    public TrackerProtocol.AnnounceResponse announce(
            URI tracker, byte[] infoHash, byte[] peerId, long downloaded,
            long left, long uploaded, int listenPort,Duration budget) throws IOException {
        budget=NativeNetworkBudget.cap(budget);
        long deadline=System.nanoTime()+budget.toNanos();
        InetSocketAddress target = policy.target(tracker);
        if (!"udp".equalsIgnoreCase(tracker.getScheme())) throw new IllegalArgumentException("Expected UDP tracker");
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(target);
            channel.configureBlocking(false);
            long connectionId = 0;
            boolean connected = false;
            for (int attempt=0;attempt<3 && !connected;attempt++) {
                int transaction = ThreadLocalRandom.current().nextInt();
                byte[] connect = exchange(channel,TrackerProtocol.udpConnectRequest(transaction),transaction,NativeNetworkBudget.cap(Duration.ofNanos(
                        Math.min(Duration.ofSeconds(2 << attempt).toNanos(),
                                Math.max(1,deadline-System.nanoTime())))));
                if (connect != null) {
                    connectionId = TrackerProtocol.parseUdpConnectResponse(connect,transaction);
                    connected = true;
                }
            }
            if (!connected) throw new IOException("UDP Tracker connect timeout");
            for (int attempt=0;attempt<3;attempt++) {
                int transaction = ThreadLocalRandom.current().nextInt();
                byte[] request = TrackerProtocol.udpAnnounceRequest(connectionId,transaction,infoHash,
                        peerId,downloaded,left,uploaded,listenPort);
                byte[] reply = exchange(channel,request,transaction,NativeNetworkBudget.cap(Duration.ofNanos(
                        Math.min(Duration.ofSeconds(2 << attempt).toNanos(),
                                Math.max(1,deadline-System.nanoTime())))));
                if (reply != null) return TrackerProtocol.parseUdpAnnounceResponse(reply,transaction);
            }
            throw new IOException("UDP Tracker announce timeout");
        }
    }
    private static byte[] exchange(DatagramChannel channel,byte[] request,int transactionId,Duration timeout) throws IOException {
        // Drain delayed responses from a previous transaction before sending a new request.
        ByteBuffer receive = ByteBuffer.allocate(20 + 6 * 10000);
        while (channel.read(receive) > 0) receive.clear();
        channel.write(ByteBuffer.wrap(request));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            NativeNetworkBudget.check();
            receive.clear();
            int read = channel.read(receive);
            if (read >= 8) {
                receive.flip();
                if (receive.getInt(4) != transactionId) continue;
                receive.position(0);
                byte[] result = new byte[read];
                receive.get(result);
                return result;
            }
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted",e); }
        }
        return null;
    }
}
