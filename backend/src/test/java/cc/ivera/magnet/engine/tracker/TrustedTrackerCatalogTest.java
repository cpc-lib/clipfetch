package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TrustedTrackerCatalogTest {
    private static byte[] list() {
        return ("udp://tracker.example:1337/announce\n"
                +"udp://tracker.example:1337/announce\n"
                +"http://tracker.example/announce\n"
                +"https://not-supported.example/announce\n"
                +"http://localhost/announce#bad\n").getBytes(StandardCharsets.UTF_8);
    }
    @Test void optInCacheAndStrictProtocolFiltering() throws Exception {
        AtomicInteger fetches=new AtomicInteger();
        var catalog=new TrustedTrackerCatalog(()->{fetches.incrementAndGet();return list();});
        assertTrue(catalog.candidates(false).isEmpty());
        assertEquals(0,fetches.get());
        assertEquals(3,catalog.candidates(true).size());
        assertEquals("https",catalog.candidates(true).getFirst().getScheme());
        assertEquals(3,catalog.candidates(true).size());
        assertEquals(1,fetches.get());
    }
    @Test void productionCatalogStartsWithBundledTrackersInsteadOfBlockingOnRemoteFeeds()
            throws Exception {
        var live=TrustedTrackerCatalog.publicFeed();
        assertTrue(live.candidates(true).stream().anyMatch(uri->
                "torrent.ubuntu.com".equals(uri.getHost())),
                "Consented metadata lookups can immediately query the preapproved Ubuntu Tracker");
        assertTrue(live.candidates(false).isEmpty(),"No consent means zero public discovery");
    }
    @Test void slowTrackersCannotSerializeFourParallelPublicAnnounces() throws Exception {
        var joined=new java.util.concurrent.CountDownLatch(4);
        var source=java.util.List.of(
                java.net.URI.create("https://one.example/announce"),
                java.net.URI.create("https://two.example/announce"),
                java.net.URI.create("https://three.example/announce"),
                java.net.URI.create("https://four.example/announce"));
        var probe=new PublicTrackerProbe(new TrustedTrackerCatalog(TrustedTrackerCatalogTest::list),
                (uri,hash,id,port)->{
                    joined.countDown();
                    try {
                        if(!joined.await(1100,java.util.concurrent.TimeUnit.MILLISECONDS))
                            throw new java.io.IOException("No simultaneous probe");
                    }catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    return new TrackerProtocol.AnnounceResponse(60,java.util.List.of(
                            InetSocketAddress.createUnresolved("8.8.8.8",6881)));
                },new TrackerAddressPolicy(false));
        long started=System.nanoTime();
        var found=probe.probeUris(new byte[20],new byte[20],6881,source,4);
        assertEquals(4,found.checked().size());
        assertEquals(4,found.checked().stream().filter(PublicTrackerProbe.TrackerStatus::responding).count());
        assertEquals(1,found.peers().size());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1500);
    }

    @Test void largeHealthyTrackerResultEndsDiscoveryWithoutWaitingForSlowTrackers() throws Exception {
        var source=java.util.List.of(java.net.URI.create("https://fast.example/announce"),
                java.net.URI.create("https://slow.example/announce"));
        var fast=new java.util.ArrayList<InetSocketAddress>();
        for(int i=1;i<=28;i++)
            fast.add(InetSocketAddress.createUnresolved("8.8.8."+i,6881));
        var probe=new PublicTrackerProbe(new TrustedTrackerCatalog(TrustedTrackerCatalogTest::list),
                (uri,hash,id,port)->{
                    if(uri.getHost().equals("slow.example")) {
                        try {Thread.sleep(2500);} catch(InterruptedException cancel){
                            Thread.currentThread().interrupt();
                        }
                        return new TrackerProtocol.AnnounceResponse(60,java.util.List.of());
                    }
                    return new TrackerProtocol.AnnounceResponse(60,fast);
                },new TrackerAddressPolicy(false));
        long start=System.nanoTime();
        var answer=probe.probeUris(new byte[20],new byte[20],6881,source,2);
        assertTrue(answer.peers().size()>=24);
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
    }

    @Test void actualTrackerResponseDeterminesHealth() throws Exception {
        var catalog=new TrustedTrackerCatalog(TrustedTrackerCatalogTest::list);
        AtomicInteger calls=new AtomicInteger();
        var probe=new PublicTrackerProbe(catalog,(uri,hash,peerId,port)->{
            calls.incrementAndGet();
            if(uri.getScheme().equals("http"))throw new java.io.IOException("Fixture down");
            return new TrackerProtocol.AnnounceResponse(1800,List.of(
                    InetSocketAddress.createUnresolved("8.8.8.8",6881)));
        },new TrackerAddressPolicy(false));
        assertTrue(probe.probe(new byte[20],new byte[20],6881,false,3).checked().isEmpty());
        var result=probe.probe(new byte[20],new byte[20],6881,true,3);
        assertEquals(3,calls.get());
        assertEquals(3,result.checked().size());
        assertEquals(1,result.peers().size());
        assertTrue(result.checked().getFirst().responding());
        assertFalse(result.checked().get(1).responding());
    }
}
