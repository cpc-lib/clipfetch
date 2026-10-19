package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.dht.DhtBootstrapLookup;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Explicit public-network acceptance of the self-contained native BEP3 transport.
 * Not an automated test: no network, no download, and no persistent application DB
 * is touched during ordinary clean test runs.
 *
 * Enable with -Dnative.acceptance.torrent=<local .torrent>
 *             -Dnative.acceptance.network=true
 *
 * Only one fully SHA-1-verified piece is downloaded, in a JUnit-owned temp dir.
 * Test fails when the public environment cannot produce real, verified Piece data.
 */
class NativePublicInternetAcceptanceTest {
    @TempDir Path scratch;
    private static final String UBUNTU_EXPECTED_V1 =
            "e1fc140a6391357fa1cf08ddb70274f9c05eb88b";
    private static final SecureRandom RANDOM=new SecureRandom();

    private record Fixture(byte[] infoHash,byte[] peerId,
                           TorrentV1MetainfoParser.TorrentInfo torrent) {}

    private Fixture load() throws Exception {
        String configured=System.getProperty("native.acceptance.torrent");
        assumeTrue(configured!=null && !configured.isBlank(),
                "Offline default: supply the Ubuntu public .torrent fixture");
        Path file=Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(file),"Missing fixture: "+file);
        assertTrue(Files.size(file)<=8*1024*1024,"Metainfo too large for acceptance");
        byte[] metainfo=Files.readAllBytes(file);
        var info=new TorrentV1MetainfoParser().parse(metainfo);
        assertEquals(UBUNTU_EXPECTED_V1,info.infoHash(),
                "Public Ubuntu fixture InfoHash changed: check its provenance");
        assertFalse(VerifiedSwarmPrivacy.verifyTorrent(metainfo,info.infoHash()).privateTorrent(),
                "Never disclose a private torrent to public Tracker/DHT");
        assertEquals(info.infoHash(),VerifiedSwarmPrivacy.verifyTorrent(
                metainfo,info.infoHash()).requirePublicPermit().infoHash());
        assertTrue(info.pieceLayout().pieceCount()>0);
        byte[] id=new byte[20];RANDOM.nextBytes(id);
        System.arraycopy("-CF0001-".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0,id,0,8);
        System.out.println("NATIVE_ACCEPTANCE_METADATA hash="+info.infoHash()
                +" pieceBytes="+info.pieceLength()+" pieceCount="+info.pieceLayout().pieceCount()
                +" sizeBytes="+info.totalBytes()+" files="+info.files().size());
        return new Fixture(HexFormat.of().parseHex(info.infoHash()),id,info);
    }

    @Test void validateOfficialPublicMetainfoOffline() throws Exception {
        var fixture=load();
        assertEquals(20,fixture.infoHash().length);
    }

    @Test void discoverPublicPeersAndCheckpointOneVerifiedNetworkPiece() throws Exception {
        assumeTrue(Boolean.getBoolean("native.acceptance.network"),
                "Public network access must be explicitly opted in");
        Fixture fixture=load();
        assumeTrue(fixture.torrent().pieceLength()<=PieceBlockAssembler.MAX_BUFFERED_PIECE_BYTES,
                "This smoke test does not allocate pieces above 8MiB");
        var policy=new TrackerAddressPolicy(false);
        var candidates=new PeerCandidatePool(256,policy);
        var failures=new ArrayList<String>();
        long started=System.nanoTime();
        try(var budget=NativeNetworkBudget.open(Duration.ofSeconds(115))) {
            var probe=PublicTrackerProbe.network(policy);
            var responses=probe.probe(fixture.infoHash(),fixture.peerId(),6881,true,4);
            for(var p:responses.peers())
                candidates.offer(p,PeerCandidatePool.Source.PUBLIC_TRACKER);
            System.out.println("NATIVE_ACCEPTANCE_DISCOVERY trackerQueries="+responses.checked().size()
                    +" trackerResponding="+responses.checked().stream().filter(
                        PublicTrackerProbe.TrackerStatus::responding).count()
                    +" trackerPeers="+candidates.size());

            if(candidates.size()<2) {
                byte[] nodeId=new byte[20];RANDOM.nextBytes(nodeId);
                var found=DhtBootstrapLookup.publicNetwork(policy)
                        .find(nodeId,fixture.infoHash());
                for(var p:found.peers())
                    if(candidates.size()<256)candidates.offer(p,PeerCandidatePool.Source.DHT);
                System.out.println("NATIVE_ACCEPTANCE_DHT routers="+found.respondingRouters()
                        +" seeds="+found.seedNodes()+" totalPeers="+candidates.size());
            }
            assertTrue(candidates.size()>0,
                    "Public Tracker/DHT produced no routable Peer candidates");
            // Shuffle candidates with a reproducible per-InfoHash seed: first 40
            // tracker responses are not representative of the whole public swarm.
            long seed=java.nio.ByteBuffer.wrap(fixture.infoHash()).getLong();
            var trackerSample=PublicPeerReachabilitySampler.sample(candidates.snapshot(),
                    policy,128,8,900,Duration.ofSeconds(19),seed);
            System.out.println("NATIVE_ACCEPTANCE_TRACKER_TCP "+trackerSample.summary());
            List<InetSocketAddress> reachable=trackerSample.reachable();
            if(reachable.isEmpty()) {
                byte[] nodeId=new byte[20];RANDOM.nextBytes(nodeId);
                var discovered=DhtBootstrapLookup.publicNetwork(policy)
                        .find(nodeId,fixture.infoHash());
                var fallback=new PeerCandidatePool(256,policy);
                for(var peer:discovered.peers()) {
                    if(fallback.size()>=256)break;
                    fallback.offer(peer,PeerCandidatePool.Source.DHT);
                }
                var dhtSample=PublicPeerReachabilitySampler.sample(fallback.snapshot(),
                        policy,128,8,900,Duration.ofSeconds(19),seed ^ 0x5eed5eedL);
                reachable=dhtSample.reachable();
                System.out.println("NATIVE_ACCEPTANCE_DHT_TCP routers="
                        +discovered.respondingRouters()+" seedNodes="+discovered.seedNodes()
                        +" "+dhtSample.summary());
            }
            assertFalse(reachable.isEmpty(),
                    "Tracker/DHT public candidates sampled but no Peer TCP connection succeeded");
            var selected=Set.of(0);
            var planner=new PieceBlockPlanner(fixture.torrent(),selected);
            var session=new PeerPieceDownloadSession(policy);
            int pieceIndex=planner.wantedPieces().nextSetBit(0);
            assertTrue(pieceIndex>=0);
            byte[] verified=null;
            int attempted=0;
            for(var candidate:reachable) {
                // TCP reachability does not imply an unchoked Peer with the requested Piece.
                // Use a larger bounded candidate pool rather than spending the budget on five peers.
                if(attempted>=12 || NativeNetworkBudget.exhausted())break;
                attempted++;
                InetSocketAddress peer=candidate;
                long began=System.nanoTime();
                try {
                    verified=session.downloadPiece(peer,fixture.torrent(),
                            fixture.peerId(),planner,pieceIndex,
                            NativeNetworkBudget.cap(Duration.ofSeconds(10)));
                    System.out.println("NATIVE_ACCEPTANCE_VERIFIED_PEER ordinal="+attempted
                            +" verifiedBytes="+verified.length
                            +" elapsedMs="+Duration.ofNanos(System.nanoTime()-began).toMillis());
                    break;
                }catch(IOException | IllegalArgumentException e) {
                    String reason=e.getClass().getSimpleName()+": "+e.getMessage();
                    if(reason.length()>220)reason=reason.substring(0,220);
                    failures.add(reason);
                    System.out.println("NATIVE_ACCEPTANCE_PEER_FAILURE ordinal="+attempted
                            +" elapsedMs="+Duration.ofNanos(System.nanoTime()-began).toMillis()
                            +" cause="+reason);
                }
            }
            assertNotNull(verified,
                    "No verified Piece from "+attempted+" public Peers. Diagnostics="+failures);
            var taskId=UUID.randomUUID();
            String output=System.getProperty("native.acceptance.output");
            Path storage=output==null || output.isBlank()?scratch:Path.of(output).toAbsolutePath().normalize();
            var state=new VerifiedPieceCheckpoint(fixture.torrent(),selected,
                    storage,901,taskId);
            state.commit(pieceIndex,verified,()->{});
            var restarted=new VerifiedPieceCheckpoint(fixture.torrent(),selected,
                    storage,901,taskId);
            System.out.println("NATIVE_ACCEPTANCE_OUTPUT="+storage);
            assertEquals(verified.length,restarted.verifiedSelectedBytes());
            assertTrue(restarted.verifiedPieces().get(pieceIndex));
            System.out.println("NATIVE_ACCEPTANCE_CHECKPOINT reverifiedBytes="
                    +restarted.verifiedSelectedBytes()
                    +" totalElapsedMs="+Duration.ofNanos(System.nanoTime()-started).toMillis());
        }
    }

}
