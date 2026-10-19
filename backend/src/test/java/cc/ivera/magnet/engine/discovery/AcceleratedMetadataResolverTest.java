package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
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

class AcceleratedMetadataResolverTest {
    @Test void whenInitialPeerFailsPexCandidateCanProvideVerifiedMetadata() throws Exception {
        byte[] raw=new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(4),
                ByteString.ascii("name"),new BytesValue("test".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("piece length"),new IntegerValue(4),
                ByteString.ascii("pieces"),new BytesValue(new byte[20]))));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        var permit=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash).requirePublicPermit();
        var policy=new TrackerAddressPolicy(false);
        var discovery=new DiscoveryService(policy,
                (tracker,hashBytes,peerId,port)->List.of(InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                (hashBytes,seeds)->List.of(),10,4);
        var acceleration=new PeerDiscoveryAccelerator(policy,
                (endpoint,hashBytes,peerId)->List.of(InetSocketAddress.createUnresolved("9.9.9.9",6881)),
                (hashBytes,port,nic)->List.of(),
                (hashBytes,peerId,port)->List.of());
        AtomicInteger attempts=new AtomicInteger();
        var resolver=new MetadataResolver(discovery,(endpoint,hashBytes,peerId,timeout)->{
            attempts.incrementAndGet();
            if(endpoint.getHostString().equals("8.8.8.8"))throw new IOException("Original Peer does not support ut_metadata");
            return raw;
        },3,Duration.ofSeconds(1));
        String magnet="magnet:?xt=urn:btih:"+hash+"&tr=http%3A%2F%2Ftracker.example%2Fannounce";
        assertThrows(SecurityException.class,()->resolver.resolvePublicAccelerated(
                magnet,new byte[20],6881,List.of(),acceleration,permit,
                new PeerDiscoveryAccelerator.Options(false,true,true,true,null)));
        var resolved=resolver.resolvePublicAccelerated(
                magnet,new byte[20],6881,List.of(),acceleration,permit,
                new PeerDiscoveryAccelerator.Options(true,true,false,false,null));
        assertEquals(2,attempts.get());
        assertEquals(hash,resolved.torrent().infoHash());
        assertEquals(2,resolved.attemptedPeers());
        assertEquals("test",resolved.torrent().name());
    }
}
