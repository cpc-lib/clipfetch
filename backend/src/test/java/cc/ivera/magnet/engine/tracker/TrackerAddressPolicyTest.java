package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class TrackerAddressPolicyTest {
    private final TrackerAddressPolicy policy=new TrackerAddressPolicy(false);

    @Test void publicIpv4AndGlobalIpv6RemainAccessible() throws Exception {
        for (String literal : new String[] {
                "8.8.8.8", "1.1.1.1", "9.9.9.9",
                "2001:4860:4860::8888", "2606:4700:4700::1111"
        }) assertTrue(policy.isAllowed(InetAddress.getByName(literal)),literal);
    }
    @Test void specialPurposeRangesAreBlocked() throws Exception {
        for (String literal : new String[] {
                "0.0.0.8", "10.0.0.1", "100.64.0.1", "100.127.255.254",
                "127.0.0.1", "169.254.1.3", "172.16.4.5", "192.168.1.2",
                "192.0.0.8", "192.0.2.1", "192.88.99.9", "198.18.0.1",
                "198.51.100.10", "203.0.113.42", "224.0.0.1", "240.0.0.1",
                "255.255.255.255", "::1", "fc00::1", "fe80::1",
                "2001:db8::1", "2001::1", "2002:c000:0201::1",
                "::ffff:127.0.0.1"
        }) assertFalse(policy.isAllowed(InetAddress.getByName(literal)),literal);
    }
    @Test void rejectsLocalTargetsAndInvalidTrackerUris() {
        assertThrows(SecurityException.class,() -> policy.target(URI.create("http://127.0.0.1:80/a")));
        assertThrows(IllegalArgumentException.class,() -> policy.target(URI.create("udp://tracker.example")));
        assertEquals(443,assertDoesNotThrow(()->policy.target(URI.create("https://8.8.8.8/a"))).getPort());
        assertThrows(SecurityException.class,()->policy.target(URI.create("https://127.0.0.1/a")));
        assertThrows(IllegalArgumentException.class,() -> policy.target(URI.create("http://u:p@example.org/a")));
    }
}
