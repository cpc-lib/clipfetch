package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.dht.DhtLookupResponse;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryServiceTest {
    private static final String HASH = "00112233445566778899aabbccddeeff00112233";
    private static InetSocketAddress endpoint(int port) {
        return InetSocketAddress.createUnresolved("127.0.0.1", port);
    }

    @Test void mergesTrackerAndDhtSourcesWithoutDuplicatingPeers() {
        AtomicInteger httpCalls = new AtomicInteger(), dhtCalls = new AtomicInteger();
        var service = new DiscoveryService(new TrackerAddressPolicy(true),
                (tracker, infoHash, peerId, port) -> {
                    httpCalls.incrementAndGet();
                    assertEquals(HASH, java.util.HexFormat.of().formatHex(infoHash));
                    return List.of(endpoint(6881), endpoint(6881));
                },
                (infoHash, seeds) -> {
                    dhtCalls.incrementAndGet();
                    return List.of(endpoint(6881), endpoint(6882));
                }, 2, 4);
        var result = service.discover("magnet:?xt=urn:btih:" + HASH
                        + "&tr=http%3A%2F%2Ftracker.example%2Fannounce",
                new byte[20], 6881, List.of(new DhtLookupResponse.Node(new byte[20], endpoint(5000))));
        // A bounded pool is full from the two DHT results.
        assertEquals(2, result.candidates().size());
        assertEquals(1, httpCalls.get());
        assertEquals(1, dhtCalls.get());
        assertEquals(1, result.trackersQueried());
        assertEquals(0, result.trackerFailures());
        var first = result.candidates().stream().filter(x -> x.address().getPort() == 6881).findFirst().orElseThrow();
        assertTrue(first.sources().contains(PeerCandidatePool.Source.HTTP_TRACKER));
        assertTrue(first.sources().contains(PeerCandidatePool.Source.DHT));
    }

    @Test void failedTrackerDoesNotDiscardOtherTrackerResults() {
        var service = new DiscoveryService(new TrackerAddressPolicy(true),
                (tracker, hash, peerId, port) -> {
                    if (tracker.getHost().equals("bad.example")) throw new IOException("fixture failure");
                    return List.of(endpoint(6881));
                }, (hash, seeds) -> List.of(), 5, 4);
        var result = service.discover("magnet:?xt=urn:btih:" + HASH
                        + "&tr=http%3A%2F%2Fbad.example%2Fa"
                        + "&tr=http%3A%2F%2Fgood.example%2Fa",
                new byte[20], 6881, List.of());
        assertEquals(2, result.trackersQueried());
        assertEquals(1, result.trackerFailures());
        assertEquals(1, result.candidates().size());
    }

    @Test void refusesPrivatePeersWithDefaultPolicy() {
        var service = new DiscoveryService(new TrackerAddressPolicy(false),
                (tracker, hash, peerId, port) -> List.of(endpoint(6881)),
                (hash, seeds) -> List.of(), 5, 4);
        var result = service.discover("magnet:?xt=urn:btih:" + HASH
                        + "&tr=http%3A%2F%2Ftracker.example%2Fa",
                new byte[20], 6881, List.of());
        assertTrue(result.candidates().isEmpty());
    }

    @Test void magnetWithoutTrackerStillInvokesDhtDiscovery() {
        AtomicInteger calls = new AtomicInteger();
        var service = new DiscoveryService(new TrackerAddressPolicy(true),
                (tracker, hash, peerId, port) -> { fail("No tracker URI is supplied"); return List.of(); },
                (hash, seeds) -> {
                    calls.incrementAndGet();
                    assertTrue(seeds.isEmpty());
                    return List.of(endpoint(6881));
                }, 5, 4);
        var result = service.discover("magnet:?xt=urn:btih:" + HASH,
                new byte[20], 6881, List.of());
        assertEquals(0, result.trackersQueried());
        assertEquals(1, calls.get());
        assertEquals(1, result.candidates().size());
    }

    @Test void onlyExplicitConsentCanUseCatalogAndItsPeersStillRequirePublicAddresses() {
        AtomicInteger calls=new AtomicInteger();
        var service=new DiscoveryService(new TrackerAddressPolicy(false),
                (uri,hash,id,port)->List.of(),
                (hash,seeds)->{throw new IOException("UDP unavailable");},8,4,
                (hash,id,port)->{
                    calls.incrementAndGet();
                    return new cc.ivera.magnet.engine.tracker.PublicTrackerProbe.Result(List.of(),List.of(
                            InetSocketAddress.createUnresolved("127.0.0.1",6881),
                            InetSocketAddress.createUnresolved("8.8.8.8",6881),
                            InetSocketAddress.createUnresolved("8.8.8.8",6881)));
                });
        assertTrue(service.discover("magnet:?xt=urn:btih:"+HASH,
                new byte[20],6881,List.of()).candidates().isEmpty());
        assertEquals(0,calls.get(),"Normal discovery must never disclose to the public catalog");
        assertThrows(IllegalArgumentException.class,()->service.discoverWithPublicDiscoveryConsent(
                "invalid",new byte[20],6881,List.of()));
        assertEquals(0,calls.get(),"Validate the magnet before any catalog network traffic");
        var result=service.discoverWithPublicDiscoveryConsent("magnet:?xt=urn:btih:"+HASH,
                new byte[20],6881,List.of());
        assertEquals(1,calls.get());
        assertEquals(1,result.candidates().size());
        assertEquals("8.8.8.8",result.candidates().getFirst().address().getHostString());
        assertTrue(result.candidates().getFirst().sources().contains(PeerCandidatePool.Source.PUBLIC_TRACKER));
    }

    @Test void authorizedCatalogCanBeatSlowDhtBootstrapWithoutWaitingForUdpTimeout() {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var catalogCalls=new AtomicInteger();
        var service=new DiscoveryService(new TrackerAddressPolicy(true),
                (uri,h,id,port)->List.of(),
                (hash,seeds)->{
                    entered.countDown();
                    try{Thread.sleep(4000);}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    return List.of();
                },10,3,
                (hash,id,port)->{
                    catalogCalls.incrementAndGet();
                    try{assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    return new cc.ivera.magnet.engine.tracker.PublicTrackerProbe.Result(
                            List.of(),List.of(endpoint(6881)));
                });
        long started=System.nanoTime();
        var result=service.discoverWithPublicDiscoveryConsent(
                "magnet:?xt=urn:btih:"+HASH,new byte[20],6881,List.of());
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertEquals(1,result.candidates().size());
        assertEquals(1,catalogCalls.get());
        assertTrue(elapsed<1500,"Slow DHT must not block a consenting catalog hit: "+elapsed);
    }

    @Test void networkDiscoverySendsTlsForHttpsTrackerWithoutDhtForKnownPrivateTorrent() throws Exception {
        try(var server=new java.net.ServerSocket(0)) {
            server.setSoTimeout(1000);
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var traffic=pool.submit(()->{
                    try(var socket=server.accept()) {
                        socket.setSoTimeout(1000);
                        return socket.getInputStream().read();
                    }catch(java.net.SocketTimeoutException noConnection) {return -1;}
                });
                String tracker="https://127.0.0.1:"+server.getLocalPort()+"/announce";
                var encoder=new BencodeEncoder();
                var raw=new DictionaryValue(java.util.Map.of(
                        ByteString.ascii("length"),new IntegerValue(4),
                        ByteString.ascii("name"),new BytesValue(new byte[]{'x'}),
                        ByteString.ascii("piece length"),new IntegerValue(4),
                        ByteString.ascii("pieces"),new BytesValue(new byte[20]),
                        ByteString.ascii("private"),new IntegerValue(1)));
                byte[] torrent=encoder.encode(new DictionaryValue(java.util.Map.of(
                        ByteString.ascii("announce"),new BytesValue(
                                tracker.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                        ByteString.ascii("info"),raw)));
                String hash=new TorrentV1MetainfoParser().parse(torrent).infoHash();
                var classification=VerifiedSwarmPrivacy.verifyTorrent(torrent,hash);
                var service=DiscoveryService.network(new TrackerAddressPolicy(true));
                var result=service.discoverVerified("magnet:?xt=urn:btih:"+hash+"&tr="
                                +java.net.URLEncoder.encode(tracker,java.nio.charset.StandardCharsets.UTF_8),
                        new byte[20],6881,List.of(),classification);
                assertEquals(1,result.trackerFailures());
                assertEquals(0x16,traffic.get(3,java.util.concurrent.TimeUnit.SECONDS),
                        "HTTPS metadata tracker discovery must emit a TLS handshake, never plaintext");
            }finally {pool.shutdownNow();}
        }
    }
}
