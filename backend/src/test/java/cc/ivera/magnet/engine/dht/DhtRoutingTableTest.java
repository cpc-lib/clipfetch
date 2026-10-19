package cc.ivera.magnet.engine.dht;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DhtRoutingTableTest {
    @Test void bucketDistanceAndClosestAreCorrect() {
        byte[] local = new byte[20];
        var routing = new DhtRoutingTable(local);
        byte[] a = new byte[20], b = new byte[20];
        a[19] = 1; b[19] = 2;
        assertEquals(0,DhtRoutingTable.bucketIndex(local,a));
        assertEquals(1,DhtRoutingTable.bucketIndex(local,b));
        assertEquals(-1,DhtRoutingTable.bucketIndex(local,local));
        assertTrue(routing.add(a,InetSocketAddress.createUnresolved("8.8.8.8",6881)));
        assertTrue(routing.add(b,InetSocketAddress.createUnresolved("1.1.1.1",6881)));
        assertFalse(routing.add(a,InetSocketAddress.createUnresolved("8.8.8.8",6881)));
        assertEquals(2,routing.size());
        assertArrayEquals(a,routing.closest(a,1).getFirst().id());
        byte[] exposed = routing.closest(a,1).getFirst().id();
        Arrays.fill(exposed,(byte) 10);
        assertArrayEquals(a,routing.closest(a,1).getFirst().id());
    }
}
