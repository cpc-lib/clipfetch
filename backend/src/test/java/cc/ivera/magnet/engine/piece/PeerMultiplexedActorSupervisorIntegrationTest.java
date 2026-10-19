package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Health-aware persistent Actor supervisor: failed Peers are replaced in the
 * same round, not deferred to the next round.
 */
class PeerMultiplexedActorSupervisorIntegrationTest {
    private static final PeerWireCodec WIRE = new PeerWireCodec();
    private static final int BLOCK = PieceBlockPlanner.BLOCK_BYTES;
    private static final int PIECE = 24 * BLOCK;

    @TempDir Path root;

    @Test void actorWaitsForPeerDiscoveredAfterInitialPoolFails() throws Exception {
        byte[] content = new byte[PIECE];
        for (int i = 0; i < content.length; i++) content[i] = (byte)(i * 13 + 7);
        var info = new TorrentInfo("b".repeat(40), "actor-refresh-wait",
                List.of(new PieceLayout.FileEntry(0, "fixture.bin", 0, content.length)),
                content.length, PIECE, MessageDigest.getInstance("SHA-1").digest(content));
        var planner = new PieceBlockPlanner(info, Set.of(0));
        var session = new PeerPieceDownloadSession(new TrackerAddressPolicy(true), () -> true);
        var initialFailed = new CountDownLatch(1);
        var healthyReplies = new AtomicInteger();
        var servers = Executors.newFixedThreadPool(3);
        try (ServerSocket failing = new ServerSocket(0);
             ServerSocket healthy = new ServerSocket(0)) {
            var failedPeer = new InetSocketAddress("127.0.0.1", failing.getLocalPort());
            var healthyPeer = new InetSocketAddress("127.0.0.1", healthy.getLocalPort());
            var tracker = new PeerHealthTracker(List.of(failedPeer));
            var bits = new BitSet(); bits.set(0);
            var availability = new PeerPieceAvailability(1,
                    Map.of(failedPeer, bits, healthyPeer, bits));
            var failedFuture = servers.submit(() -> {
                try (Socket socket = failing.accept()) {
                    initialFailed.countDown();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var healthyFuture = servers.submit(() -> {
                try (Socket socket = healthy.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)2, 1);
                    PeerWireCodec.Message msg;
                    while ((msg = read(in)) != null) {
                        if (msg.id() != 6) continue;
                        reply(msg, content, out, healthyReplies);
                        out.flush();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var refreshFuture = servers.submit(() -> {
                try {
                    if (!initialFailed.await(3, TimeUnit.SECONDS))
                        throw new TimeoutException("Initial Peer did not fail");
                    Thread.sleep(300);
                    tracker.mergeDiscovered(List.of(healthyPeer), 4);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            session.beginTask(99, UUID.randomUUID(), info.infoHash());
            PeerMultiplexedRound.Download result;
            try {
                var owner = Thread.currentThread();
                session.beginRound(owner);
                try {
                    result = PeerMultiplexedRound.fetch(owner, session, info, planner,
                            List.of(0), List.of(failedPeer), new byte[20], availability,
                            1, Duration.ofSeconds(8), () -> {}, (piece, bytes) -> {}, tracker);
                } finally { session.endRound(owner); }
            } finally { session.endTask(); }
            failedFuture.get(3, TimeUnit.SECONDS);
            refreshFuture.get(3, TimeUnit.SECONDS);
            healthyFuture.get(3, TimeUnit.SECONDS);
            assertArrayEquals(content, result.verified().get(0),
                    "Actor must remain available for a late-discovered Peer");
            assertTrue(healthyReplies.get() >= 24,
                    "Late-discovered Peer must serve the full Piece");
        } finally { servers.shutdownNow(); }
    }

    @Test void failingPeersAreReplacedByHealthyPeersWithinSameRound() throws Exception {
        byte[] content = new byte[PIECE];
        for (int i = 0; i < content.length; i++) content[i] = (byte)(i * 31 + 5);
        var info = new TorrentInfo("c".repeat(40), "actor-supervisor",
                List.of(new PieceLayout.FileEntry(0, "fixture.bin", 0, content.length)),
                content.length, PIECE, MessageDigest.getInstance("SHA-1").digest(content));
        var planner = new PieceBlockPlanner(info, Set.of(0));
        var session = new PeerPieceDownloadSession(new TrackerAddressPolicy(true), () -> true);
        var failCount = new AtomicInteger();
        var healthyReplies = new AtomicInteger();
        var servers = Executors.newFixedThreadPool(3);
        try (ServerSocket failA = new ServerSocket(0);
             ServerSocket failB = new ServerSocket(0);
             ServerSocket healthy = new ServerSocket(0)) {
            // First two Peers fail immediately after handshake (EOF).
            // maxActors=2 leaves the healthy Peer idle so a failed Actor
            // can reassign to it within the same round.
            var failFutureA = servers.submit(() -> {
                try (Socket socket = failA.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)1, 1);
                    failCount.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var failFutureB = servers.submit(() -> {
                try (Socket socket = failB.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)2, 1);
                    failCount.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            // Third Peer is healthy and serves all Blocks
            var healthyFuture = servers.submit(() -> {
                try (Socket socket = healthy.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)3, 1);
                    PeerWireCodec.Message msg;
                    while ((msg = read(in)) != null) {
                        if (msg.id() != 6) continue;
                        reply(msg, content, out, healthyReplies);
                        out.flush();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var failPeerA = new InetSocketAddress("127.0.0.1", failA.getLocalPort());
            var failPeerB = new InetSocketAddress("127.0.0.1", failB.getLocalPort());
            var healthyPeer = new InetSocketAddress("127.0.0.1", healthy.getLocalPort());
            var bits = new BitSet(); bits.set(0);
            var availability = new PeerPieceAvailability(1, Map.of(
                    failPeerA, bits, failPeerB, bits, healthyPeer, bits));
            session.beginTask(100, UUID.randomUUID(), info.infoHash());
            PeerMultiplexedRound.Download result;
            try {
                var owner = Thread.currentThread();
                session.beginRound(owner);
                try {
                    result = PeerMultiplexedRound.fetch(owner, session, info, planner,
                            List.of(0), List.of(failPeerA, failPeerB, healthyPeer),
                            new byte[20], availability, 2, Duration.ofSeconds(25), () -> {});
                } finally { session.endRound(owner); }
            } finally { session.endTask(); }
            failFutureA.get(4, TimeUnit.SECONDS);
            failFutureB.get(4, TimeUnit.SECONDS);
            healthyFuture.get(4, TimeUnit.SECONDS);
            assertEquals(2, failCount.get(), "Both failing Peers must have been attempted");
            assertTrue(healthyReplies.get() >= 24, "Healthy Peer must serve all Blocks");
            assertArrayEquals(content, result.verified().get(0));
            assertEquals(1, result.verified().size());
            assertEquals(2, result.failedPeers(), "Two Peers must be recorded as failed");
            // Actor reassignment must have occurred within the same round
            assertTrue(result.actorPeerReassignments() > 0,
                    "Actor must be reassigned to healthy Peer within same round");
        } finally { servers.shutdownNow(); }
    }

    @Test void samePeerNeverLeasedToTwoActorsSimultaneously() throws Exception {
        byte[] content = new byte[PIECE];
        for (int i = 0; i < content.length; i++) content[i] = (byte)(i * 17 + 9);
        var info = new TorrentInfo("d".repeat(40), "peer-mutex",
                List.of(new PieceLayout.FileEntry(0, "fixture.bin", 0, content.length)),
                content.length, PIECE, MessageDigest.getInstance("SHA-1").digest(content));
        var planner = new PieceBlockPlanner(info, Set.of(0));
        var session = new PeerPieceDownloadSession(new TrackerAddressPolicy(true), () -> true);
        var activeLeases = new ConcurrentHashMap<String, AtomicInteger>();
        var violations = new AtomicInteger();
        var servers = Executors.newFixedThreadPool(2);
        try (ServerSocket listenerA = new ServerSocket(0);
             ServerSocket listenerB = new ServerSocket(0)) {
            var peerA = new InetSocketAddress("127.0.0.1", listenerA.getLocalPort());
            var peerB = new InetSocketAddress("127.0.0.1", listenerB.getLocalPort());
            var futureA = servers.submit(() -> {
                try (Socket socket = listenerA.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)1, 1);
                    PeerWireCodec.Message msg;
                    while ((msg = read(in)) != null) {
                        if (msg.id() != 6) continue;
                        String key = peerA.getHostString() + ":" + peerA.getPort();
                        var counter = activeLeases.computeIfAbsent(key, k -> new AtomicInteger());
                        int current = counter.incrementAndGet();
                        if (current > 1) violations.incrementAndGet();
                        reply(msg, content, out, new AtomicInteger());
                        out.flush();
                        counter.decrementAndGet();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var futureB = servers.submit(() -> {
                try (Socket socket = listenerB.accept()) {
                    socket.setSoTimeout(8000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    handshake(in, out, (byte)2, 1);
                    PeerWireCodec.Message msg;
                    while ((msg = read(in)) != null) {
                        if (msg.id() != 6) continue;
                        String key = peerB.getHostString() + ":" + peerB.getPort();
                        var counter = activeLeases.computeIfAbsent(key, k -> new AtomicInteger());
                        int current = counter.incrementAndGet();
                        if (current > 1) violations.incrementAndGet();
                        reply(msg, content, out, new AtomicInteger());
                        out.flush();
                        counter.decrementAndGet();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var bits = new BitSet(); bits.set(0);
            var availability = new PeerPieceAvailability(1, Map.of(peerA, bits, peerB, bits));
            session.beginTask(101, UUID.randomUUID(), info.infoHash());
            PeerMultiplexedRound.Download result;
            try {
                var owner = Thread.currentThread();
                session.beginRound(owner);
                try {
                    result = PeerMultiplexedRound.fetch(owner, session, info, planner,
                            List.of(0), List.of(peerA, peerB), new byte[20],
                            availability, 2, Duration.ofSeconds(10), () -> {});
                } finally { session.endRound(owner); }
            } finally { session.endTask(); }
            futureA.get(4, TimeUnit.SECONDS);
            futureB.get(4, TimeUnit.SECONDS);
            assertEquals(0, violations.get(), "Same Peer must never be leased to two Actors simultaneously");
            assertEquals(1, result.verified().size());
        } finally { servers.shutdownNow(); }
    }

    @Test void targetActorCountScalesWithVerifiedPeers() throws Exception {
        // This test verifies the supervisor's target Actor calculation logic
        // without requiring actual TCP connections.
        var tracker = new PeerHealthTracker(List.of(
                InetSocketAddress.createUnresolved("8.8.8.1", 6881),
                InetSocketAddress.createUnresolved("8.8.8.2", 6881),
                InetSocketAddress.createUnresolved("8.8.8.3", 6881)));
        // No verified Peers: should start with exploration slots
        int target = PeerMultiplexedRound.computeTargetActors(tracker, 8, 4);
        assertTrue(target <= 8, "Without verified Peers, target should be bounded by exploration slots");
        // With verified Peers: should scale up
        tracker.success(InetSocketAddress.createUnresolved("8.8.8.1", 6881), 262144, 100_000_000L);
        tracker.success(InetSocketAddress.createUnresolved("8.8.8.2", 6881), 262144, 100_000_000L);
        target = PeerMultiplexedRound.computeTargetActors(tracker, 8, 4);
        assertTrue(target >= 2, "With verified Peers, target should scale up");
    }

    @Test void quietSwarmDrainsGracefullyWithoutWaitingFullTimeout() throws Exception {
        byte[] content = new byte[PIECE];
        for (int i = 0; i < content.length; i++) content[i] = (byte)(i * 7 + 3);
        var info = new TorrentInfo("d".repeat(40), "quiet-swarm-drain",
                List.of(new PieceLayout.FileEntry(0, "fixture.bin", 0, content.length)),
                content.length, PIECE, MessageDigest.getInstance("SHA-1").digest(content));
        var planner = new PieceBlockPlanner(info, Set.of(0));
        var session = new PeerPieceDownloadSession(new TrackerAddressPolicy(true), () -> true);
        var servers = Executors.newFixedThreadPool(1);
        try (ServerSocket failing = new ServerSocket(0)) {
            var failedPeer = new InetSocketAddress("127.0.0.1", failing.getLocalPort());
            var tracker = new PeerHealthTracker(List.of(failedPeer));
            var bits = new BitSet(); bits.set(0);
            var availability = new PeerPieceAvailability(1, Map.of(failedPeer, bits));
            servers.submit(() -> {
                try (Socket socket = failing.accept()) {
                    // Close immediately so peer fails
                } catch (Exception ignored) {}
            });
            session.beginTask(101, UUID.randomUUID(), info.infoHash());
            long started = System.nanoTime();
            try {
                var owner = Thread.currentThread();
                session.beginRound(owner);
                try {
                    // Give a large 25-second timeout, but quiet swarm must drain in ~3-5 seconds
                    PeerMultiplexedRound.fetch(owner, session, info, planner,
                            List.of(0), List.of(failedPeer), new byte[20], availability,
                            1, Duration.ofSeconds(25), () -> {}, (piece, bytes) -> {}, tracker);
                } finally { session.endRound(owner); }
            } finally { session.endTask(); }
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            assertTrue(elapsedSeconds < 10,
                    "Quiet swarm must drain gracefully in under 10 seconds instead of waiting full 25s timeout (took " + elapsedSeconds + "s)");
        } finally { servers.shutdownNow(); }
    }

    private static void handshake(InputStream in, OutputStream out, byte id, int pieces) throws Exception {
        var offered = WIRE.decodeHandshake(ByteBuffer.wrap(
                in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH))).orElseThrow();
        byte[] peer = new byte[20]; Arrays.fill(peer, id);
        out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                new byte[8], offered.infoHash(), peer)).array());
        if (read(in).id() != 2) throw new IOException("Missing interested");
        byte[] bitfield = new byte[(pieces + 7) / 8];
        for (int i = 0; i < pieces; i++) bitfield[i / 8] |= (byte)(0x80 >>> (i % 8));
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(5, bitfield)).array());
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1, new byte[0])).array());
        out.flush();
    }

    private static void reply(PeerWireCodec.Message req, byte[] data, OutputStream out, AtomicInteger total) throws Exception {
        var b = ByteBuffer.wrap(req.payload());
        int p = b.getInt(), start = b.getInt(), len = b.getInt();
        if (p != 0 || start < 0 || len != BLOCK || start + len > PIECE)
            throw new IOException("Invalid Block");
        var payload = ByteBuffer.allocate(8 + len).putInt(p).putInt(start)
                .put(data, start, len).array();
        out.write(WIRE.encodeMessage(new PeerWireCodec.Message(7, payload)).array());
        total.incrementAndGet();
    }

    private static PeerWireCodec.Message read(InputStream in) throws IOException {
        var prefix = in.readNBytes(4);
        if (prefix.length == 0) return null;
        if (prefix.length != 4) throw new EOFException();
        int len = ByteBuffer.wrap(prefix).getInt();
        if (len < 0 || len > 65536) throw new IOException("Invalid frame length");
        var body = in.readNBytes(len);
        if (body.length != len) throw new EOFException();
        return WIRE.decodeMessage(ByteBuffer.wrap(
                ByteBuffer.allocate(4 + len).putInt(len).put(body).array())).orElseThrow();
    }
}
