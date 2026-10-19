package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.dht.DhtBootstrapLookup;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in FULL-download verification harness for ISSUE-001 / ISSUE-003.
 * Never runs during ordinary builds. Enable with:
 *   -Dnative.acceptance.torrent=<local .torrent>
 *   -Dnative.acceptance.network=true
 *   -Dnative.full.verify=true
 *   -Dnative.full.output=<persistent storage dir>
 *   [-Dnative.full.minutes=<wall-clock budget, default 360>]
 *   [-Dnative.full.parallel=<bounded parallel Piece workers, 1-8, default 4>]
 * Store durable checkpoints OUTSIDE Maven's target directory so clean builds
 * cannot silently delete verified public-network downloads.
 *
 * Uses the same production building blocks as the Worker: PieceDownloadCoordinator
 * (bounded parallel=1..8, attempts=8, per-peer 10s) + VerifiedPieceCheckpoint (re-verifies
 * every claimed Piece from disk on open) + one PeerHealthTracker retained across
 * rounds. The fixed task UUID makes interrupted runs RESUME from the durable
 * checkpoint; PARTIAL output never claims a complete download.
 */
class NativeFullDownloadVerificationTest {
    private static final String UBUNTU_EXPECTED_V1 = System.getProperty(
            "native.full.expected.infohash", "e1fc140a6391357fa1cf08ddb70274f9c05eb88b");
    private static final String UBUNTU_EXPECTED_SHA256 = System.getProperty(
            "native.full.expected.sha256", "dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9");
    /** Stable identity: re-running this test resumes the same durable checkpoint. */
    private static final UUID TASK_ID = UUID.fromString("1f0e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test void downloadEntireTorrentWithDurableResumeEvidence() throws Exception {
        assumeTrue(Boolean.getBoolean("native.acceptance.network")
                        && Boolean.getBoolean("native.full.verify"),
                "Opt-in full download verification is disabled");
        String configured = System.getProperty("native.acceptance.torrent");
        assumeTrue(configured != null && !configured.isBlank(), "Missing .torrent fixture path");
        String output = System.getProperty("native.full.output");
        assumeTrue(output != null && !output.isBlank(), "Missing persistent output directory");
        Path storage = requireDurableOutput(Path.of(output), Path.of("target"));
        int parallel = boundedParallelWorkers(System.getProperty("native.full.parallel", "4"));
        Files.createDirectories(storage);

        byte[] metainfo = Files.readAllBytes(Path.of(configured).toAbsolutePath().normalize());
        var info = new TorrentV1MetainfoParser().parse(metainfo);
        assertEquals(UBUNTU_EXPECTED_V1, info.infoHash(), "Unexpected fixture InfoHash");
        assertFalse(VerifiedSwarmPrivacy.verifyTorrent(metainfo, info.infoHash()).privateTorrent(),
                "Never disclose a private torrent to public Tracker/DHT");

        Set<Integer> allFiles = new HashSet<>();
        for (var file : info.files()) allFiles.add(file.index());
        byte[] peerId = new byte[20];
        RANDOM.nextBytes(peerId);
        System.arraycopy("-CF0001-".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, peerId, 0, 8);

        var checkpoint = new VerifiedPieceCheckpoint(info, allFiles, storage, 901, TASK_ID);
        long totalBytes = checkpoint.selectedTotalBytes();
        // Mirrors NativeTorrentConfiguration: reuse is opt-in via native.full.reuse.
        boolean reuse = Boolean.getBoolean("native.full.reuse");
        System.out.println("NATIVE_FULL_BEGIN pieces=" + info.pieceLayout().pieceCount()
                + " totalBytes=" + totalBytes
                + " resumedBytes=" + checkpoint.verifiedSelectedBytes()
                + " resumedPercent=" + pct(checkpoint.progressPercent())
                + " peerConnectionReuse=" + reuse
                + " parallelWorkers=" + parallel);

        var policy = new TrackerAddressPolicy(false);
        var session = new PeerPieceDownloadSession(policy, () -> reuse);
        byte[] infoHash = HexFormat.of().parseHex(info.infoHash());
        List<InetSocketAddress> pool = discoverPool(policy, infoHash, peerId, 0);
        assertFalse(pool.isEmpty(), "No TCP-reachable public Peers");
        var health = new PeerHealthTracker(pool);

        long wallBudgetMs = Long.parseLong(System.getProperty("native.full.minutes", "360")) * 60_000L;
        long started = System.nanoTime();
        long startBytes = checkpoint.verifiedSelectedBytes();
        var lastLoggedBytes = new AtomicLong(checkpoint.verifiedSelectedBytes());
        int round = 0, consecutiveEmpty = 0, refreshes = 0;
        while (System.nanoTime() - started < wallBudgetMs * 1_000_000L) {
            if (checkpoint.verifiedSelectedBytes() >= totalBytes) break;
            long remainingMs = wallBudgetMs - (System.nanoTime() - started) / 1_000_000L;
            if (remainingMs < 30_000) break;
            round++;
            long roundStarted = System.nanoTime();
            long roundStartBytes = checkpoint.verifiedSelectedBytes();
            var coordinator = new PieceDownloadCoordinator(checkpoint, session::downloadPiece,
                    pool, peerId, 8, Duration.ofSeconds(10), parallel, session);
            coordinator.useRoundHealth(health);
            PieceDownloadCoordinator.RoundResult result = null;
            IOException roundFailure = null;
            try (var ignored = NativeNetworkBudget.open(
                    Duration.ofMillis(Math.min(remainingMs - 5_000, 10 * 60_000L)))) {
                result = coordinator.runRound(256, (piece, wire, verified) -> {
                    long logged = lastLoggedBytes.get();
                    if (verified - logged >= 64L * 1024 * 1024
                            && lastLoggedBytes.compareAndSet(logged, verified)) {
                        System.out.println("NATIVE_FULL_PROGRESS verifiedBytes=" + verified
                                + " percent=" + pct(checkpoint.progressPercent()));
                    }
                });
            } catch (IOException failure) {
                roundFailure = failure; // committed Pieces stay durable; continue next round
            }
            long elapsedMs = (System.nanoTime() - roundStarted) / 1_000_000L;
            long committedBytes = checkpoint.verifiedSelectedBytes() - roundStartBytes;
            System.out.println("NATIVE_FULL_ROUND round=" + round
                    + (result == null ? " interrupted=" + roundFailure.getMessage()
                            : " committed=" + result.committedPieces()
                            + " failed=" + result.failedPieces().size()
                            + " peerAttempts=" + result.peerAttempts())
                    + " committedBytes=" + committedBytes
                    + " verifiedBytes=" + checkpoint.verifiedSelectedBytes()
                    + " percent=" + pct(checkpoint.progressPercent())
                    + " roundSeconds=" + String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0)
                    + " roundMiBps=" + mibps(committedBytes, elapsedMs));
            if (committedBytes <= 0) {
                consecutiveEmpty++;
                if (consecutiveEmpty >= 2) {
                    if (refreshes >= 3) break;
                    refreshes++;
                    consecutiveEmpty = 0;
                    pool = discoverPool(policy, infoHash, peerId, refreshes);
                    health = new PeerHealthTracker(pool, health);
                    System.out.println("NATIVE_FULL_PEER_REFRESH ordinal=" + refreshes
                            + " pool=" + pool.size());
                    if (pool.isEmpty()) break;
                }
            } else {
                consecutiveEmpty = 0;
            }
        }

        long elapsedTotalMs = (System.nanoTime() - started) / 1_000_000L;
        long gained = checkpoint.verifiedSelectedBytes() - startBytes;
        // Durable recovery proof: a brand-new checkpoint instance re-verifies every
        // claimed Piece from the bytes actually on disk before progress is trusted.
        var recovered = new VerifiedPieceCheckpoint(info, allFiles, storage, 901, TASK_ID);
        assertEquals(checkpoint.verifiedSelectedBytes(), recovered.verifiedSelectedBytes(),
                "Recovered checkpoint disagrees with the online checkpoint");
        boolean complete = recovered.verifiedSelectedBytes() >= totalBytes;
        System.out.println("NATIVE_FULL_RESULT status=" + (complete ? "COMPLETE" : "PARTIAL")
                + " verifiedBytes=" + recovered.verifiedSelectedBytes()
                + " totalBytes=" + totalBytes
                + " percent=" + pct(recovered.progressPercent())
                + " gainedBytes=" + gained
                + " wallSeconds=" + String.format(Locale.ROOT, "%.1f", elapsedTotalMs / 1000.0)
                + " avgMiBps=" + mibps(gained, elapsedTotalMs)
                + " rounds=" + round + " peerRefreshes=" + refreshes
                + " peerConnectionReuse=" + reuse);
        assertTrue(gained > 0 || startBytes > 0, "No verified Piece progress at all");
        if (complete) {
            // The engine finalizes a fully verified task by renaming every
            // selected .part file to its real torrent name.
            recovered.finalizeVerifiedFiles();
            var file = info.files().get(0);
            Path finalFile = storage.resolve(file.path()).toAbsolutePath().normalize();
            assertTrue(finalFile.startsWith(storage), "Unexpected output location");
            assertTrue(Files.isRegularFile(finalFile), "Missing completed torrent file");
            assertEquals(info.totalBytes(), Files.size(finalFile), "Completed size mismatch");
            assertFalse(Files.exists(storage.resolve(file.path() + ".clipfetch.part")),
                    "Finalized download left a .part file behind");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(finalFile)) {
                byte[] buffer = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            System.out.println("NATIVE_FULL_SHA256 file=" + finalFile
                    + " size=" + Files.size(finalFile)
                    + " sha256=" + actualSha256);
            assertEquals(UBUNTU_EXPECTED_SHA256, actualSha256, "Completed ISO SHA-256 mismatch");
        }
    }

    /** Tracker-first public Peer discovery with DHT fallback; returns TCP-reachable endpoints. */
    private List<InetSocketAddress> discoverPool(TrackerAddressPolicy policy, byte[] infoHash,
                                                 byte[] peerId, int salt) throws Exception {
        var candidates = new PeerCandidatePool(256, policy);
        var responses = PublicTrackerProbe.network(policy).probe(infoHash, peerId, 6881, true, 4);
        for (var peer : responses.peers()) candidates.offer(peer, PeerCandidatePool.Source.PUBLIC_TRACKER);
        long seed = java.nio.ByteBuffer.wrap(infoHash).getLong() ^ (0x9E3779B9L * (salt + 1));
        var sample = PublicPeerReachabilitySampler.sample(candidates.snapshot(), policy,
                256, 8, 900, Duration.ofSeconds(29), seed);
        System.out.println("NATIVE_FULL_DISCOVERY trackerResponding="
                + responses.checked().stream().filter(PublicTrackerProbe.TrackerStatus::responding).count()
                + "/" + responses.checked().size()
                + " trackerPeers=" + candidates.size() + " " + sample.summary());
        List<InetSocketAddress> reachable = sample.reachable();
        if (reachable.size() < 16) {
            byte[] nodeId = new byte[20];
            RANDOM.nextBytes(nodeId);
            var found = DhtBootstrapLookup.publicNetwork(policy).find(nodeId, infoHash);
            var dhtCandidates = new PeerCandidatePool(256, policy);
            for (var peer : found.peers()) dhtCandidates.offer(peer, PeerCandidatePool.Source.DHT);
            var dhtSample = PublicPeerReachabilitySampler.sample(dhtCandidates.snapshot(), policy,
                    256, 8, 900, Duration.ofSeconds(29), seed ^ 0x5eed5eedL);
            System.out.println("NATIVE_FULL_DHT routers=" + found.respondingRouters()
                    + " seeds=" + found.seedNodes() + " " + dhtSample.summary());
            var merged = new java.util.LinkedHashSet<>(reachable);
            merged.addAll(dhtSample.reachable());
            reachable = List.copyOf(merged);
        }
        return reachable.size() > 256 ? reachable.subList(0, 256) : reachable;
    }

    static int boundedParallelWorkers(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 8)
            throw new IllegalArgumentException("native.full.parallel must be between 1 and 8");
        return parsed;
    }

    static Path requireDurableOutput(Path chosen, Path buildTarget) {
        Path absolute = chosen.toAbsolutePath().normalize();
        if (absolute.startsWith(buildTarget.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Store resumable ISO output outside Maven target: " + absolute);
        return absolute;
    }

    @Test void rejectsTransientBuildDirectoryAndUnboundedParallelismOffline() {
        assertEquals(4, boundedParallelWorkers("4"));
        assertEquals(8, boundedParallelWorkers("8"));
        assertThrows(IllegalArgumentException.class, () -> boundedParallelWorkers("0"));
        assertThrows(IllegalArgumentException.class, () -> boundedParallelWorkers("9"));
        assertThrows(IllegalArgumentException.class, () -> requireDurableOutput(
                Path.of("target", "download-checkpoint"), Path.of("target")));
        assertNotNull(requireDurableOutput(Path.of("..", "persistent-native-test"), Path.of("target")));
    }

    private static String pct(double percent) {
        return String.format(Locale.ROOT, "%.4f", percent);
    }

    private static String mibps(long bytes, long elapsedMs) {
        double value = elapsedMs > 0 ? bytes / 1024.0 / 1024.0 / (elapsedMs / 1000.0) : 0;
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
