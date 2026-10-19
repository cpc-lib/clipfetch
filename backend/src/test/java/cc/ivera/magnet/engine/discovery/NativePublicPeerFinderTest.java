package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
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

class NativePublicPeerFinderTest {
    static VerifiedSwarmPrivacy.PublicPermit permit(byte[] raw) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        return VerifiedSwarmPrivacy.verifyRawInfo(raw,hash).requirePublicPermit();
    }
    static byte[] info() {
        return new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(4),
                ByteString.ascii("name"),new BytesValue("test".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("piece length"),new IntegerValue(4),
                ByteString.ascii("pieces"),new BytesValue(new byte[20]))));
    }
    private static InetSocketAddress peer(String ip) {
        return InetSocketAddress.createUnresolved(ip,6881);
    }

    @Test void verifiedPublicTrackerCandidatesCanBeExtendedByPexWithoutLsd() throws Exception {
        var token=permit(info());
        var hash=HexFormat.of().parseHex(token.infoHash());
        AtomicInteger pexCalls=new AtomicInteger(),trackerCalls=new AtomicInteger();
        var policy=new TrackerAddressPolicy(false);
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->{pexCalls.incrementAndGet();return List.of(peer("9.9.9.9"));},
                (h,p,nic)->{fail("LSD must stay off");return List.of();},
                (h,id,p)->{fail("Public tracker catalog is called only once");return List.of();});
        var finder=new NativePublicPeerFinder(
                (h,id,p)->{trackerCalls.incrementAndGet();return List.of(peer("8.8.8.8"));},
                accelerator,policy);
        var candidates=finder.find(hash,new byte[20],6881,token);
        assertEquals(2,candidates.size());
        assertTrue(candidates.contains(peer("9.9.9.9")));
        assertEquals(1,pexCalls.get());
        assertEquals(1,trackerCalls.get());
        assertThrows(SecurityException.class,
                ()->finder.find(new byte[20],new byte[20],6881,token));
        assertEquals(1,trackerCalls.get()); // no network before InfoHash mismatch rejection
    }

    @Test void collaborativeFinderStartsDownloadsBeforeExtraDiscoveryWhenPeersAvailable()
            throws Exception {
        var permit=permit(info());
        var accelerator=new PeerDiscoveryAccelerator(new TrackerAddressPolicy(false),
                (peer,hash,id)->{fail("PeX must not block initial Piece downloads");return List.of();},
                (hash,port,nic)->{fail("LSD must not block");return List.of();},
                (hash,id,port)->{fail("No extra catalog pass");return List.of();});
        var endpoints=new java.util.ArrayList<InetSocketAddress>();
        for(int i=1;i<=24;i++)endpoints.add(peer("8.8.8."+i));
        var finder=new NativePublicPeerFinder((hash,id,port)->endpoints,
                (urls,hash,id,port)->List.of(),accelerator,
                new TrackerAddressPolicy(false),(node,hash)->{
                    fail("DHT must not delay already discovered public Peers");return List.of();
                },()->true,true,256);
        var found=finder.find(HexFormat.of().parseHex(permit.infoHash()),
                new byte[20],6881,permit);
        assertEquals(24,found.size());
    }

    @Test void thoroughFinderConsultsDhtAndPexEvenWhenTrackerReturnsEnoughPeers()
            throws Exception {
        var permit=permit(info());
        AtomicInteger dhtCalls=new AtomicInteger(),pexCalls=new AtomicInteger();
        var accelerator=new PeerDiscoveryAccelerator(new TrackerAddressPolicy(false),
                (peer,hash,id)->{pexCalls.incrementAndGet();return List.of(peer("9.9.9.9"));},
                (hash,port,nic)->List.of(),
                (hash,id,port)->List.of());
        var endpoints=new java.util.ArrayList<InetSocketAddress>();
        for(int i=1;i<=24;i++)endpoints.add(peer("8.8.8."+i));
        var finder=new NativePublicPeerFinder((hash,id,port)->endpoints,
                (urls,hash,id,port)->List.of(),accelerator,
                new TrackerAddressPolicy(false),(node,hash)->{
                    dhtCalls.incrementAndGet();return List.of(peer("8.8.100.1"));
                },()->true,true,256);
        byte[] hash=HexFormat.of().parseHex(permit.infoHash());
        // Fast path: tracker already supplied >=16 Peers, DHT/PeX stay skipped.
        var quick=finder.find(hash,new byte[20],6881,permit);
        assertEquals(24,quick.size());
        assertEquals(0,dhtCalls.get());
        assertEquals(0,pexCalls.get());
        // Thorough path: same swarm, but refresh must consult DHT and PeX so a
        // mostly-dead tracker roster cannot starve the transfer.
        var thorough=finder.findThorough(hash,new byte[20],6881,permit);
        assertEquals(1,dhtCalls.get());
        assertTrue(pexCalls.get()>0);
        assertTrue(thorough.contains(peer("8.8.100.1")));
        assertTrue(thorough.contains(peer("9.9.9.9")));
        assertTrue(thorough.contains(peer("8.8.8.1")));
    }

    @Test void trackerEmptyUsesDhtOnlyWhenExplicitlyEnabled() throws Exception {
        var token=permit(info());
        byte[] hash=HexFormat.of().parseHex(token.infoHash());
        var enabled=new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicInteger dhtCalls=new AtomicInteger(),pexCalls=new AtomicInteger();
        var policy=new TrackerAddressPolicy(false);
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->{
                    pexCalls.incrementAndGet();
                    return List.of(peer("9.9.9.9"));
                },(h,port,nic)->{fail("LSD is disabled");return List.of();},
                (h,id,port)->{fail("No repeated trackers");return List.of();});
        var finder=new NativePublicPeerFinder((h,id,port)->List.of(),
                accelerator,policy,
                (nodeId,infoHash)->{
                    dhtCalls.incrementAndGet();
                    assertEquals(20,nodeId.length);
                    assertArrayEquals(hash,infoHash);
                    return List.of(peer("192.168.1.6"),peer("8.8.8.8"),
                            peer("8.8.8.8"));
                },enabled::get);
        assertTrue(finder.find(hash,new byte[20],6881,token).isEmpty());
        assertEquals(0,dhtCalls.get());
        enabled.set(true);
        var list=finder.find(hash,new byte[20],6881,token);
        assertEquals(1,dhtCalls.get());
        assertEquals(1,pexCalls.get());
        assertEquals(2,list.size());
        assertTrue(list.contains(peer("8.8.8.8")));
        assertTrue(list.contains(peer("9.9.9.9")));
        assertFalse(list.contains(peer("192.168.1.6")));
    }

    @Test void respondingTrackerSkipsDhtEvenWhenEnabledAndHashIsGuardedBeforeAnyNetwork()
            throws Exception {
        var token=permit(info());
        AtomicInteger trackerCalls=new AtomicInteger();
        var policy=new TrackerAddressPolicy(false);
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->List.of(),
                (h,port,nic)->{fail("LSD disabled");return List.of();},
                (h,id,port)->{fail("No additional trackers");return List.of();});
        var finder=new NativePublicPeerFinder(
                (h,id,port)->{trackerCalls.incrementAndGet();return List.of(peer("8.8.8.8"));},
                accelerator,policy,
                (nodeId,hash)->{fail("DHT must not be queried");return List.of();},
                ()->true);
        byte[] hash=HexFormat.of().parseHex(token.infoHash());
        assertEquals(List.of(peer("8.8.8.8")),finder.find(hash,new byte[20],6881,token));
        assertThrows(SecurityException.class,()->finder.find(new byte[20],
                new byte[20],6881,token));
        assertThrows(SecurityException.class,()->finder.find(hash,
                new byte[20],6881,null));
        assertEquals(1,trackerCalls.get());
    }

    @Test void failedTrackerMayFallBackToDhtAndFailedDhtReturnsNoCandidates()
            throws Exception {
        var token=permit(info());
        var policy=new TrackerAddressPolicy(false);
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->List.of(),
                (h,port,nic)->List.of(),
                (h,id,port)->List.of());
        var finder=new NativePublicPeerFinder(
                (h,id,port)->{throw new java.io.IOException("Tracker down");},
                accelerator,policy,
                (nodeId,hash)->List.of(peer("9.9.9.9")),
                ()->true);
        assertEquals(List.of(peer("9.9.9.9")),finder.find(
                HexFormat.of().parseHex(token.infoHash()),new byte[20],6881,token));
        var noFinder=new NativePublicPeerFinder(
                (h,id,port)->List.of(),
                accelerator,policy,
                (nodeId,hash)->{throw new java.io.IOException("DHT down");},
                ()->true);
        assertTrue(noFinder.find(HexFormat.of().parseHex(token.infoHash()),
                new byte[20],6881,token).isEmpty());
    }

    @Test void samplesAcrossFullTrackerListRatherThanOnlyFirstSixtyFour() throws Exception {
        var permit=permit(info());
        var policy=new TrackerAddressPolicy(false);
        var candidates=new java.util.ArrayList<InetSocketAddress>();
        for(int i=0;i<160;i++)
            candidates.add(InetSocketAddress.createUnresolved("8.8.8.8",5000+i));
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->List.of(),
                (h,port,nic)->List.of(),
                (h,id,port)->List.of());
        var finder=new NativePublicPeerFinder(
                (h,id,port)->candidates,accelerator,policy);
        byte[] randomizedPeerId=new byte[20];
        java.util.Arrays.fill(randomizedPeerId,(byte)8);
        var found=finder.find(HexFormat.of().parseHex(permit.infoHash()),
                randomizedPeerId,6881,permit);
        assertEquals(64,found.size());
        assertEquals(64,found.stream().distinct().count());
        assertTrue(found.stream().anyMatch(p->p.getPort()>=5064),
                "Sampling must include candidates outside the tracker list prefix");
        assertTrue(found.stream().anyMatch(p->p.getPort()<5064),
                "Sampling should keep some candidates from the prefix as well");
    }

    @Test void explicitPeerBudgetAllowsMoreThan64WithoutLeakingUnboundedCandidates()
            throws Exception {
        var permit=permit(info());
        var policy=new TrackerAddressPolicy(false);
        var candidates=new java.util.ArrayList<InetSocketAddress>();
        for(int i=0;i<220;i++)
            candidates.add(InetSocketAddress.createUnresolved("8.8.8.8",5000+i));
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->List.of(),
                (h,port,nic)->List.of(),
                (h,id,port)->List.of());
        var finder=new NativePublicPeerFinder((h,id,port)->candidates,
                (urls,h,id,port)->List.of(),accelerator,policy,
                (node,hash)->List.of(),()->false,true,128);
        var found=finder.find(HexFormat.of().parseHex(permit.infoHash()),
                new byte[20],6881,permit);
        assertEquals(128,found.size());
        assertEquals(128,found.stream().distinct().count());
        assertThrows(IllegalArgumentException.class,()->
                new NativePublicPeerFinder((h,id,port)->candidates,
                        (urls,h,id,port)->List.of(),accelerator,policy,
                        (node,hash)->List.of(),()->false,true,257));
    }

    @Test void candidateShuffleIsDeterministicPerSeedAndCapsUntrustedInput() {
        var addresses=new java.util.ArrayList<InetSocketAddress>();
        for(int i=0;i<3000;i++)
            addresses.add(InetSocketAddress.createUnresolved("8.8.8.8",2000+i));
        var first=NativePublicPeerFinder.shuffledCandidates(addresses,99L);
        var repeat=NativePublicPeerFinder.shuffledCandidates(addresses,99L);
        assertEquals(first,repeat);
        assertEquals(2048,first.size());
        assertTrue(first.stream().allMatch(endpoint->endpoint.getPort()<4048));
        assertNotEquals(first,NativePublicPeerFinder.shuffledCandidates(addresses,101L));
    }

    @Test void ownerScopedHttpsTrackerIsTriedBeforePublicCatalog() throws Exception {
        byte[] raw=info();
        var bare=permit(raw);
        var privacy=VerifiedSwarmPrivacy.verifyRawInfo(raw,bare.infoHash());
        var owner=privacy.requirePublicPermit(List.of("https://torrent.ubuntu.com/announce"));
        var policy=new TrackerAddressPolicy(false);
        AtomicInteger catalogCalls=new AtomicInteger(),ownerCalls=new AtomicInteger();
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (p,h,id)->List.of(),(h,port,nic)->List.of(),(h,id,port)->List.of());
        var finder=new NativePublicPeerFinder((h,id,p)->{
                    catalogCalls.incrementAndGet();
                    return List.of(peer("9.9.9.9"));
                },(uris,h,id,p)->{
                    ownerCalls.incrementAndGet();
                    assertEquals(owner.trackers(),uris);
                    return List.of(peer("8.8.8.8"));
                },accelerator,policy,(node,h)->List.of(),()->false);
        byte[] hash=HexFormat.of().parseHex(owner.infoHash());
        assertEquals(List.of(peer("8.8.8.8")),finder.find(hash,new byte[20],6881,owner));
        assertEquals(1,ownerCalls.get());
        assertEquals(0,catalogCalls.get());
        assertThrows(SecurityException.class,()->finder.find(new byte[20],
                new byte[20],6881,owner));
        var fallback= new NativePublicPeerFinder((h,id,p)->{
                    catalogCalls.incrementAndGet();
                    return List.of(peer("9.9.9.9"));
                },(uris,h,id,p)->List.of(),accelerator,policy,(node,h)->List.of(),()->false);
        assertEquals(List.of(peer("9.9.9.9")),fallback.find(hash,new byte[20],6881,owner));
        assertEquals(1,catalogCalls.get());
    }

    @Test void noInitialPeersMeansNoPexAndNoLanBroadcasts() throws Exception {
        var token=permit(info());
        var policy=new TrackerAddressPolicy(false);
        var accelerator=new PeerDiscoveryAccelerator(policy,
                (endpoint,h,id)->{fail("Cannot PEX without connected candidate");return List.of();},
                (h,p,nic)->{fail("LSD disabled");return List.of();},
                (h,id,p)->{fail("No additional tracker queries");return List.of();});
        var finder=new NativePublicPeerFinder((h,id,p)->List.of(),accelerator,policy);
        assertTrue(finder.find(HexFormat.of().parseHex(token.infoHash()),
                new byte[20],6881,token).isEmpty());
        assertThrows(SecurityException.class,()->finder.find(
                HexFormat.of().parseHex(token.infoHash()),new byte[20],6881,null));
    }
}
