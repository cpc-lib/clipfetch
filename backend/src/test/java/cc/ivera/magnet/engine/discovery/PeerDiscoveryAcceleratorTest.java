package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PeerDiscoveryAcceleratorTest {
    static byte[] publicInfo() {
        return new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(4),
                ByteString.ascii("name"),new BytesValue("test".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("piece length"),new IntegerValue(4),
                ByteString.ascii("pieces"),new BytesValue(new byte[20]))));
    }
    static String hash(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
    }
    static InetSocketAddress candidate(String ip,int port) {
        return InetSocketAddress.createUnresolved(ip,port);
    }
    @Test void publicSwarmCanMergePexAndTrackerPeers() throws Exception {
        byte[] raw=publicInfo();
        var permit=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw)).requirePublicPermit();
        byte[] target=HexFormat.of().parseHex(hash(raw));
        AtomicInteger calls=new AtomicInteger();
        var component=new PeerDiscoveryAccelerator(new TrackerAddressPolicy(false),
                (peer,hash,id) -> {
                    calls.incrementAndGet();return List.of(candidate("9.9.9.9",6881));
                },
                (hash,port,nic) -> List.of(),
                (hash,id,port) -> {calls.incrementAndGet();
                    return List.of(candidate("1.1.1.1",6881));});
        var initial=List.of(new PeerCandidatePool.Candidate(
                candidate("8.8.8.8",6881),Set.of(PeerCandidatePool.Source.DHT)));
        var result=component.enrich(initial,target,new byte[20],6881,permit,
                new PeerDiscoveryAccelerator.Options(true,true,false,true,null));
        assertEquals(3,result.candidates().size());
        assertEquals(2,calls.get());
        assertEquals(1,result.pexConnections());
        assertEquals(1,result.trackerPeers());
        assertTrue(result.candidates().stream()
                .anyMatch(p->p.sources().contains(PeerCandidatePool.Source.PEX)));
        assertTrue(result.candidates().stream()
                .anyMatch(p->p.sources().contains(PeerCandidatePool.Source.PUBLIC_TRACKER)));
    }
    @Test void invalidOrMismatchedPermitNeverContactsExtraNetworks() throws Exception {
        var raw=publicInfo();
        var permit=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw)).requirePublicPermit();
        var component=new PeerDiscoveryAccelerator(new TrackerAddressPolicy(false),
                (peer,hash,id)->{fail("PEX must be off");return List.of();},
                (hash,port,nic)->{fail("LSD must be off");return List.of();},
                (hash,id,port)->{fail("Public tracker lookup must be off");return List.of();});
        var options=new PeerDiscoveryAccelerator.Options(true,true,true,true,null);
        assertThrows(SecurityException.class,()->component.enrich(
                List.of(),new byte[20],new byte[20],6881,null,options));
        assertThrows(SecurityException.class,()->component.enrich(
                List.of(),new byte[20],new byte[20],6881,permit,options));
        assertTrue(component.enrich(List.of(),HexFormat.of().parseHex(hash(raw)),
                new byte[20],6881,permit,
                new PeerDiscoveryAccelerator.Options(false,true,true,true,null))
                .candidates().isEmpty());
    }
    @Test void onlyPrivateIpv4MayEnterLanCandidatePool() {
        var pool=new PeerCandidatePool(2,new TrackerAddressPolicy(false));
        assertTrue(pool.offerLan(new InetSocketAddress("192.168.1.23",6881)));
        assertFalse(pool.offerLan(new InetSocketAddress("8.8.8.8",6881)));
        assertFalse(pool.offerLan(candidate("192.168.1.24",6881)));
        assertTrue(pool.snapshot().getFirst().sources().contains(PeerCandidatePool.Source.LSD));
    }
}
