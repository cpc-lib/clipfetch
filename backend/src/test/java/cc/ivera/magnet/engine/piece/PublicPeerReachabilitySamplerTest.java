package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PublicPeerReachabilitySamplerTest {
    private static PeerCandidatePool.Candidate peer(String address,int port) {
        return new PeerCandidatePool.Candidate(
                InetSocketAddress.createUnresolved(address,port),
                Set.of(PeerCandidatePool.Source.PUBLIC_TRACKER));
    }

    @Test void shuffledSamplingFindsReachableCandidatesOutsideFirstForty() throws Exception {
        List<PeerCandidatePool.Candidate> sources=new ArrayList<>();
        for(int i=0;i<80;i++)sources.add(peer("8.8.8.8",6000+i));
        AtomicInteger attempts=new AtomicInteger();
        var sampler=(PublicPeerReachabilitySampler.TcpConnector)(p,timeout)->{
            attempts.incrementAndGet();
            if(p.getPort()<6040)throw new ConnectException("Peer refused TCP");
        };
        var result=PublicPeerReachabilitySampler.sample(sources,
                new TrackerAddressPolicy(false),64,4,800,
                Duration.ofSeconds(3),20261015L,sampler);
        assertEquals(80,result.summary().candidates());
        assertEquals(64,result.summary().sampled());
        assertEquals(64,attempts.get());
        assertTrue(result.summary().reachable()>0);
        assertTrue(result.summary().refused()>0);
        assertEquals(result.summary().reachable(),result.reachable().size());
        assertEquals(64,result.summary().distinctPorts());
        assertTrue(result.reachable().stream().allMatch(p->p.getAddress()!=null &&
                p.getAddress().getHostAddress().equals("8.8.8.8") &&
                p.getPort()>=6040));
        var again=PublicPeerReachabilitySampler.sample(sources,
                new TrackerAddressPolicy(false),64,4,800,
                Duration.ofSeconds(3),20261015L,sampler);
        assertEquals(result.reachable(),again.reachable(),
                "Same InfoHash seed should produce reproducible diagnostics");
    }

    @Test void privateAndLoopbackTrackerAddressesAreNeverConnected() throws Exception {
        var entries=List.of(peer("127.0.0.1",6881),peer("192.168.1.22",6881),
                peer("8.8.8.8",6881));
        AtomicInteger connects=new AtomicInteger();
        var result=PublicPeerReachabilitySampler.sample(entries,
                new TrackerAddressPolicy(false),3,3,500,
                Duration.ofSeconds(2),17L,(target,timeout)->{
                    assertEquals("8.8.8.8",target.getAddress().getHostAddress());
                    connects.incrementAndGet();
                });
        assertEquals(1,connects.get());
        assertEquals(1,result.summary().reachable());
        assertEquals(2,result.summary().other());
        assertEquals(3,result.summary().sampled());
        assertEquals(1,result.summary().distinctPorts());
        assertEquals(3,result.summary().commonBtPorts());
    }

    @Test void distinguishesRefusedTimedOutAndUnreachableWithoutLeakingEndpoints()
            throws Exception {
        var entries=List.of(peer("8.8.8.8",6000),peer("8.8.8.8",6001),
                peer("8.8.8.8",6002));
        var result=PublicPeerReachabilitySampler.sample(entries,
                new TrackerAddressPolicy(false),3,3,500,
                Duration.ofSeconds(2),2026L,(target,timeout)->{
                    switch(target.getPort()) {
                        case 6000 -> throw new ConnectException("refused");
                        case 6001 -> throw new SocketTimeoutException("timeout");
                        case 6002 -> throw new NoRouteToHostException("unreachable");
                        default -> fail("Unexpected Peer");
                    }
                });
        assertEquals(1,result.summary().refused());
        assertEquals(1,result.summary().timedOut());
        assertEquals(1,result.summary().unreachable());
        assertTrue(result.reachable().isEmpty());
    }

    @Test void samplerBoundsNetworkConcurrencyAndWorkTime() throws Exception {
        var entries=new ArrayList<PeerCandidatePool.Candidate>();
        for(int i=0;i<36;i++)entries.add(peer("8.8.8.8",4000+i));
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        long began=System.nanoTime();
        var result=PublicPeerReachabilitySampler.sample(entries,
                new TrackerAddressPolicy(false),30,3,500,
                Duration.ofMillis(150),3L,(target,timeout)->{
                    int count=active.incrementAndGet();
                    peak.accumulateAndGet(count,Math::max);
                    try {Thread.sleep(75);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();
                        throw new SocketTimeoutException("cancelled probe");}
                    finally {active.decrementAndGet();}
                });
        assertTrue(peak.get()<=3);
        assertEquals(30,result.summary().sampled());
        assertTrue(result.summary().timedOut()>0);
        assertTrue(Duration.ofNanos(System.nanoTime()-began).toMillis()<1500);
    }
}
