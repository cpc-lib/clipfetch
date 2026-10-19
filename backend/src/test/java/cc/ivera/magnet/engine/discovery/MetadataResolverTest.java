package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MetadataResolverTest {
    private static byte[] infoDictionary() {
        return new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"), new IntegerValue(4),
                ByteString.ascii("name"), new BytesValue("sample.txt".getBytes(StandardCharsets.UTF_8)),
                ByteString.ascii("piece length"), new IntegerValue(4),
                ByteString.ascii("pieces"), new BytesValue(new byte[20])
        )));
    }

    @Test void fallsBackToNextPeerAndPreservesRawInfoHash() throws Exception {
        byte[] raw = infoDictionary();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        var discovery = new DiscoveryService(new TrackerAddressPolicy(true),
                (uri, ih, peerId, port) -> List.of(
                        InetSocketAddress.createUnresolved("127.0.0.1", 10001),
                        InetSocketAddress.createUnresolved("127.0.0.1", 10002)),
                (ih, seeds) -> List.of(), 2, 2);
        AtomicInteger attempts = new AtomicInteger();
        var resolver = new MetadataResolver(discovery, (endpoint, ih, peerId, timeout) -> {
            attempts.incrementAndGet();
            if (endpoint.getPort() == 10001) throw new IOException("Offline fixture");
            return raw.clone();
        }, 2, Duration.ofSeconds(2));
        var result = resolver.resolve("magnet:?xt=urn:btih:" + hash
                        + "&tr=http%3A%2F%2Ftracker.example%2Fannounce",
                new byte[20], 6881, List.of());
        assertEquals(2, attempts.get());
        assertEquals(2, result.attemptedPeers());
        assertEquals(hash, result.torrent().infoHash());
        assertEquals(1, result.torrent().files().size());
        assertEquals("sample.txt", result.torrent().files().getFirst().path());
        assertEquals(4, result.torrent().totalBytes());
    }

    @Test void refusesInvalidMetadataAndRespectsAttemptLimit() throws Exception {
        byte[] raw = infoDictionary();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        var discovery = new DiscoveryService(new TrackerAddressPolicy(true),
                (uri, ih, peerId, port) -> List.of(
                        InetSocketAddress.createUnresolved("127.0.0.1", 10001),
                        InetSocketAddress.createUnresolved("127.0.0.1", 10002)),
                (ih, seeds) -> List.of(), 2, 2);
        AtomicInteger calls = new AtomicInteger();
        var resolver = new MetadataResolver(discovery, (endpoint, ih, peerId, timeout) -> {
            calls.incrementAndGet();
            return new byte[] {'d', 'e'}; // wrong SHA1 and invalid info dictionary
        }, 1, Duration.ofSeconds(2));
        assertThrows(IOException.class, () -> resolver.resolve(
                "magnet:?xt=urn:btih:" + hash
                        + "&tr=http%3A%2F%2Ftracker.example%2Fannounce",
                new byte[20], 6881, List.of()));
        assertEquals(1, calls.get());
    }

    @Test void slowFirstPeerDoesNotBlockFastVerifiedMetadataPeer() throws Exception {
        byte[] raw=infoDictionary();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        var discovery=new DiscoveryService(new TrackerAddressPolicy(true),
                (uri,h,id,port)->List.of(
                        InetSocketAddress.createUnresolved("127.0.0.1",10001),
                        InetSocketAddress.createUnresolved("127.0.0.1",10002)),
                (h,seeds)->List.of(),4,4);
        var active=new java.util.concurrent.atomic.AtomicInteger();
        var resolver=new MetadataResolver(discovery,(peer,h,id,timeout)->{
            active.incrementAndGet();
            if(peer.getPort()==10001) {
                try {Thread.sleep(1800);}
                catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Slow Peer was cancelled",cancelled);
                }
            }
            return raw.clone();
        },2,Duration.ofSeconds(3));
        long started=System.nanoTime();
        var found=resolver.resolve("magnet:?xt=urn:btih:"+hash+
                "&tr=http%3A%2F%2Ftracker.example%2Fannounce",
                new byte[20],6881,List.of());
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertEquals(hash,found.torrent().infoHash());
        assertEquals(2,active.get());
        assertTrue(elapsed<1200,"Slow first Peer must not delay verified result: "+elapsed);
    }

    @Test void peerSelectionDistributesAttemptsAcrossSubnetsBeforeRetryingSisterIps() {
        var pool=new cc.ivera.magnet.engine.peer.PeerCandidatePool(
                16,new TrackerAddressPolicy(true));
        for(int i=30;i<40;i++)
            pool.offer(InetSocketAddress.createUnresolved("67.21.89."+i,6881),
                    cc.ivera.magnet.engine.peer.PeerCandidatePool.Source.PUBLIC_TRACKER);
        pool.offer(InetSocketAddress.createUnresolved("185.125.190.59",6881),
                cc.ivera.magnet.engine.peer.PeerCandidatePool.Source.PUBLIC_TRACKER);
        pool.offer(InetSocketAddress.createUnresolved("1.82.151.23",6881),
                cc.ivera.magnet.engine.peer.PeerCandidatePool.Source.DHT);
        var choices=MetadataResolver.diversePeers(pool.snapshot());
        assertEquals(12,choices.size());
        assertEquals("67.21.89.30",choices.get(0).address().getHostString());
        assertEquals("185.125.190.59",choices.get(1).address().getHostString());
        assertEquals("1.82.151.23",choices.get(2).address().getHostString());
        assertEquals("67.21.89.31",choices.get(3).address().getHostString());
    }

    @Test void consentedCatalogLookupDoesNotChangeOrdinaryResolverPrivacy() throws Exception {
        byte[] raw=infoDictionary();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        AtomicInteger catalogCalls=new AtomicInteger();
        var discovery=new DiscoveryService(new TrackerAddressPolicy(true),
                (uri,h,id,port)->List.of(),(h,seeds)->List.of(),4,4,
                (h,id,port)->{
                    catalogCalls.incrementAndGet();
                    return new cc.ivera.magnet.engine.tracker.PublicTrackerProbe.Result(List.of(),
                            List.of(InetSocketAddress.createUnresolved("127.0.0.1",6881)));
                });
        var resolver=new MetadataResolver(discovery,(endpoint,h,id,timeout)->raw.clone(),
                1,Duration.ofSeconds(1));
        String magnet="magnet:?xt=urn:btih:"+hash;
        assertThrows(IOException.class,()->resolver.resolve(magnet,new byte[20],6881,List.of()));
        assertEquals(0,catalogCalls.get());
        var result=resolver.resolveWithPublicDiscoveryConsent(magnet,new byte[20],6881,List.of());
        assertEquals(1,catalogCalls.get());
        assertEquals(hash,result.torrent().infoHash());
        assertEquals(1,result.attemptedPeers());
    }
}
