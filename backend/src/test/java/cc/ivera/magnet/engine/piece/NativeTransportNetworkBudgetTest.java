package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.dht.DhtQueryClient;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import cc.ivera.magnet.engine.tracker.UdpTrackerClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NativeTransportNetworkBudgetTest {
    @Test void silentDhtNodeCannotConsumeItsNominalThreeSecondTimeout() throws Exception {
        try(var listener=new DatagramSocket(0)) {
            int port=listener.getLocalPort();
            var client=new DhtQueryClient(new TrackerAddressPolicy(true));
            long started=System.nanoTime();
            try(var limited=NativeNetworkBudget.open(Duration.ofMillis(130))) {
                assertThrows(IOException.class,()->client.query(
                        new InetSocketAddress("127.0.0.1",port),
                        new byte[20],new byte[20],"get_peers",Duration.ofSeconds(3)));
            }
            long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();
            assertTrue(elapsed<1000,"UDP DHT should obey inherited deadline: "+elapsed+"ms");
        }
    }

    @Test void silentUdpTrackerCannotExhaustAllConnectAndAnnounceRetries() throws Exception {
        try(var listener=new DatagramSocket(0)) {
            int port=listener.getLocalPort();
            var client=new UdpTrackerClient(new TrackerAddressPolicy(true));
            long started=System.nanoTime();
            try(var limited=NativeNetworkBudget.open(Duration.ofMillis(140))) {
                assertThrows(IOException.class,()->client.announce(
                        URI.create("udp://127.0.0.1:"+port+"/announce"),
                        new byte[20],new byte[20],0,1,0,6881));
            }
            long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();
            assertTrue(elapsed<1000,"UDP Tracker retries should obey deadline: "+elapsed+"ms");
        }
    }
}
