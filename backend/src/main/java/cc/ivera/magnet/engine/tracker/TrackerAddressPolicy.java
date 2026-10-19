package cc.ivera.magnet.engine.tracker;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Fail-closed outbound address policy for untrusted Tracker/DHT/Peer endpoints.
 * Resolve once and connect to the resulting pinned IP; never reconnect by hostname.
 * The private-address override is only for explicitly controlled local test fixtures.
 */
public final class TrackerAddressPolicy {
    private final boolean allowPrivateForTests;

    public TrackerAddressPolicy(boolean allowPrivateForTests) {
        this.allowPrivateForTests = allowPrivateForTests;
    }

    public InetAddress resolve(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isBlank()) throw new UnknownHostException("Empty endpoint");
        InetAddress[] addresses = InetAddress.getAllByName(hostname);
        if (addresses.length == 0) throw new UnknownHostException(hostname);
        // Deny mixed safe/unsafe DNS answers; this also avoids quietly accepting a
        // public result when a name simultaneously resolves to a private target.
        for (InetAddress address : addresses) {
            if (!isAllowed(address)) throw new SecurityException("Endpoint resolves to a restricted address");
        }
        return addresses[0];
    }

    public boolean isAllowed(InetAddress address) {
        if (address == null) return false;
        if (allowPrivateForTests) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;

        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            // Block reserved, non-routable, carrier NAT, benchmarking, documentation,
            // link-local, multicast, future-use and shared address space.
            int a = raw[0] & 255;
            int b = raw[1] & 255;
            int c = raw[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false; // 100.64/10
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && (b == 0 || b == 168)) return false;
            if (a == 192 && b == 88 && c == 99) return false;
            if (a == 192 && b == 0 && c == 2) return false;
            if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
            if (a == 203 && b == 0 && c == 113) return false;
            return true;
        }
        if (!(address instanceof Inet6Address)) return false;

        // Only globally routed unicast 2000::/3, excluding special-purpose ranges
        // (documentation, Teredo, 6to4, ORCHID/benchmark etc.).
        int first = raw[0] & 255, second = raw[1] & 255;
        if ((first & 0xe0) != 0x20) return false;
        if (first == 0x20 && second == 0x01) {
            int third = raw[2] & 255;
            int fourth = raw[3] & 255;
            if (third == 0x0d && fourth == 0xb8) return false; // 2001:db8::/32
            if (third == 0x00 && fourth == 0x00) return false; // 2001::/32 Teredo
            if (third == 0x00 && fourth == 0x10) return false; // 2001:10::/28
            if (third == 0x00 && fourth == 0x20) return false; // 2001:20::/28
        }
        if (first == 0x20 && second == 0x02) return false; // 2002::/16 6to4
        return true;
    }

    public InetSocketAddress target(URI uri) throws UnknownHostException {
        if (uri == null) throw new IllegalArgumentException("Missing endpoint");
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("udp") || scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https")))
            throw new IllegalArgumentException("Only UDP, HTTP and HTTPS endpoints supported");
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid endpoint URI");
        int port = uri.getPort() == -1 ? (scheme.equalsIgnoreCase("http") ? 80
                : scheme.equalsIgnoreCase("https") ? 443 : -1) : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid endpoint port");
        return new InetSocketAddress(resolve(uri.getHost()), port);
    }
}
