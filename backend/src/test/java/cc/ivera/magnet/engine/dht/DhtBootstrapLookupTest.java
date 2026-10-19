package cc.ivera.magnet.engine.dht;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DhtBootstrapLookupTest {
    private static InetSocketAddress peer(int port) {
        return InetSocketAddress.createUnresolved("8.8.8.8",port);
    }

    @Test void failedRouterDoesNotDiscardOtherBootstrapResults() {
        AtomicInteger iterativeCalls=new AtomicInteger();
        var lookup=new DhtBootstrapLookup(List.of(peer(6881),peer(6882)),
                (endpoint,local,hash,timeout) -> {
                    if (endpoint.getPort()==6881) throw new IOException("Offline router");
                    return new DhtLookupResponse(new byte[20],
                            List.of(new DhtLookupResponse.Node(new byte[20],peer(6883))),
                            List.of(peer(7001)),new byte[]{1});
                },
                (local,hash,seeds) -> {
                    iterativeCalls.incrementAndGet();
                    assertEquals(2,seeds.size());
                    return List.of(peer(7001),peer(7002));
                },10,Duration.ofSeconds(1));
        var result=lookup.find(new byte[20],new byte[20]);
        assertEquals(1,result.respondingRouters());
        assertEquals(2,result.seedNodes());
        assertEquals(2,result.peers().size());
        assertEquals(1,iterativeCalls.get());
    }

    @Test void bootstrapWithoutResponsesTerminatesWithoutIterating() {
        var lookup=new DhtBootstrapLookup(List.of(peer(6881)),
                (endpoint,id,hash,timeout) -> { throw new IOException("Timed out"); },
                (id,hash,nodes) -> { fail("No nodes should be queried");return List.of(); },
                3,Duration.ofSeconds(1));
        var result=lookup.find(new byte[20],new byte[20]);
        assertEquals(0,result.respondingRouters());
        assertTrue(result.peers().isEmpty());
    }
}
