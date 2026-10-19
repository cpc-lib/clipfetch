package cc.ivera.magnet.engine.dht;

import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IterativePeerLookupTest {
    private static final TrackerAddressPolicy TEST_POLICY = new TrackerAddressPolicy(true);
    private static InetSocketAddress endpoint(int port) {
        return InetSocketAddress.createUnresolved("127.0.0.1",port);
    }
    private static DhtLookupResponse.Node node(int id,int port) {
        byte[] hash = new byte[20]; hash[19]=(byte)id;
        return new DhtLookupResponse.Node(hash,endpoint(port));
    }

    @Test void traversesNodesWithoutRevisitingOrDuplicatingPeers() {
        AtomicInteger queries = new AtomicInteger();
        var engine = new IterativePeerLookup((address,local,target,timeout) -> {
            queries.incrementAndGet();
            return new DhtLookupResponse(new byte[20],
                    address.getPort()==7001 ? List.of(node(2,7002),node(1,7001)) :
                            List.of(node(1,7001)),
                    List.of(endpoint(6881),endpoint(6881)),new byte[]{1});
        },TEST_POLICY,10,20,20,Duration.ofSeconds(3),Duration.ofMillis(500));
        var result=engine.lookup(new byte[20],new byte[20],List.of(node(1,7001)));
        assertEquals(2,result.queriedNodes());
        assertEquals(2,result.discoveredNodes());
        assertEquals(1,result.peers().size());
        assertEquals(2,queries.get());
    }
    @Test void enforcesQueryAndPeerBudgets() {
        var engine = new IterativePeerLookup((address,local,target,timeout) ->
                new DhtLookupResponse(new byte[20],List.of(node(2,7002)),
                        List.of(endpoint(6881),endpoint(6882)),null),
                TEST_POLICY,1,1,10,Duration.ofSeconds(3),Duration.ofSeconds(1));
        var result=engine.lookup(new byte[20],new byte[20],List.of(node(1,7001)));
        assertEquals(1,result.queriedNodes());
        assertEquals(1,result.peers().size());
    }
    @Test void rejectsRestrictedBootstrapAddress() {
        AtomicInteger count=new AtomicInteger();
        var engine=new IterativePeerLookup((addr,id,hash,timeout) -> {
            count.incrementAndGet(); return new DhtLookupResponse(new byte[20],List.of(),List.of(),null);
        },new TrackerAddressPolicy(false),10,10,10,Duration.ofSeconds(1),Duration.ofMillis(100));
        var result=engine.lookup(new byte[20],new byte[20],List.of(node(1,7001)));
        assertEquals(0,count.get());
        assertEquals(0,result.queriedNodes());
    }
}
