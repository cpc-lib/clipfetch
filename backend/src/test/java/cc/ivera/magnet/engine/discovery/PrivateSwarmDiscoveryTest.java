package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.dht.DhtLookupResponse;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PrivateSwarmDiscoveryTest {
    private static byte[] info(boolean isPrivate) {
        return new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(4),
                ByteString.ascii("name"),new BytesValue("test".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("piece length"),new IntegerValue(4),
                ByteString.ascii("pieces"),new BytesValue(new byte[20]),
                ByteString.ascii("private"),new IntegerValue(isPrivate ? 1 : 0))));
    }
    private static String hash(byte[] info) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(info));
    }
    @Test void knownPrivateTorrentUsesExplicitTrackerOnlyEvenWithDhtBootstrap() throws Exception {
        byte[] raw=info(true);
        String hash=hash(raw);
        byte[] complete = new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("announce"),new BytesValue(
                        "http://tracker.example/announce".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("info"),new cc.ivera.magnet.engine.codec.BencodeDecoder().decode(raw))));
        var classification=VerifiedSwarmPrivacy.verifyTorrent(complete,hash);
        assertTrue(classification.privateTorrent());
        assertEquals(1,classification.privateTrackerAllowlist().size());
        AtomicInteger trackerCalls=new AtomicInteger();
        var discovery=new DiscoveryService(new TrackerAddressPolicy(true),
                (tracker,h,id,port)->{
                    trackerCalls.incrementAndGet();
                    return List.of(InetSocketAddress.createUnresolved("127.0.0.1",6881));
                },
                (h,nodes)->{fail("DHT must never run for a verified private torrent");return List.of();},
                5,4,(h,id,port)->{throw new AssertionError("Private torrent must never use public catalog");});
        var magnet="magnet:?xt=urn:btih:"+hash+"&tr=http%3A%2F%2Ftracker.example%2Fannounce"
                +"&tr=http%3A%2F%2Fevil.example%2Fannounce";
        var seeds=List.of(new DhtLookupResponse.Node(new byte[20],
                InetSocketAddress.createUnresolved("8.8.8.8",6881)));
        var result=discovery.discoverVerified(magnet,new byte[20],6881,seeds,classification);
        assertEquals(1,trackerCalls.get());
        assertEquals(1,result.candidates().size());
        // BEP9 raw info does not authenticate the announce list: deny all extra trackers.
        var rawClassification=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash);
        assertTrue(rawClassification.privateTrackerAllowlist().isEmpty());
        var noTrackers=discovery.discoverVerified(magnet,new byte[20],6881,seeds,rawClassification);
        assertEquals(0,noTrackers.trackersQueried());
        assertTrue(noTrackers.candidates().isEmpty());
        assertEquals(1,trackerCalls.get());
        assertThrows(SecurityException.class, classification::requirePublicPermit);
        assertThrows(SecurityException.class,()->discovery.discoverVerified(
                "magnet:?xt=urn:btih:"+"0".repeat(40),new byte[20],6881,seeds,classification));
        assertEquals(1,trackerCalls.get());
    }

    @Test void noNetworkBeforeAcceleratedPermissionHashMatch() throws Exception {
        var raw=info(false);
        var permit=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw)).requirePublicPermit();
        AtomicInteger contacted=new AtomicInteger();
        var discovery=new DiscoveryService(new TrackerAddressPolicy(true),
                (uri,h,id,port)->{contacted.incrementAndGet();return List.of();},
                (h,nodes)->{contacted.incrementAndGet();return List.of();},5,4);
        var accelerator=new PeerDiscoveryAccelerator(new TrackerAddressPolicy(true),
                (p,h,id)->{fail("No PEX");return List.of();},
                (h,port,nic)->{fail("No LAN");return List.of();},
                (h,id,port)->{fail("No extra tracker");return List.of();});
        assertThrows(SecurityException.class,()->discovery.discoverAccelerated(
                "magnet:?xt=urn:btih:"+"0".repeat(40)+"&tr=http%3A%2F%2Ftracker.example%2Fa",
                new byte[20],6881,List.of(),accelerator,permit,
                new PeerDiscoveryAccelerator.Options(true,true,true,true,null)));
        assertEquals(0,contacted.get());
    }
}
