package cc.ivera.magnet.engine.lsd;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Explicit opt-in local multicast discovery. Disabled by default;
 * no background listener, no global multicast, no internet Peer discovery.
 */
public final class LsdPeerDiscovery {
    private final LsdAnnouncementCodec codec=new LsdAnnouncementCodec();

    public List<InetSocketAddress> discover(byte[] infoHash,int listenPort,
            NetworkInterface networkInterface,Duration budget,boolean enabled) throws IOException {
        if(!enabled) return List.of();
        if(networkInterface==null || !networkInterface.isUp() || !networkInterface.supportsMulticast()
                || networkInterface.isLoopback() || networkInterface.isPointToPoint())
            throw new IllegalArgumentException("LSD requires an explicit multicast-capable LAN interface");
        if(budget==null || budget.isZero() || budget.isNegative()
                || budget.compareTo(Duration.ofSeconds(5))>0)
            throw new IllegalArgumentException("Invalid local discovery budget");
        boolean hasPrivateIpv4=networkInterface.inetAddresses()
                .anyMatch(ip -> ip instanceof Inet4Address && ip.isSiteLocalAddress());
        if(!hasPrivateIpv4) throw new IllegalArgumentException("LSD requires a private LAN IPv4 interface");
        String cookie=Long.toHexString(ThreadLocalRandom.current().nextLong())+"abcd1234";
        byte[] announcement=codec.encode(infoHash,listenPort,cookie);
        InetAddress group=InetAddress.getByName(LsdAnnouncementCodec.MULTICAST_IP);
        long deadline=System.nanoTime()+budget.toNanos();
        List<InetSocketAddress> peers=new ArrayList<>();
        HashSet<String> known=new HashSet<>();
        try(MulticastSocket socket=new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(LsdAnnouncementCodec.MULTICAST_PORT));
            socket.setNetworkInterface(networkInterface);
            socket.setTimeToLive(1);
            socket.joinGroup(new InetSocketAddress(group,LsdAnnouncementCodec.MULTICAST_PORT),networkInterface);
            try {
                socket.send(new DatagramPacket(announcement,announcement.length,group,
                        LsdAnnouncementCodec.MULTICAST_PORT));
                while(peers.size()<64 && !Thread.currentThread().isInterrupted()) {
                    long left=deadline-System.nanoTime();
                    if(left<=0)break;
                    socket.setSoTimeout((int)Math.max(1,Math.min(1000,(left+999999)/1000000)));
                    DatagramPacket packet=new DatagramPacket(new byte[1500],1500);
                    try { socket.receive(packet); }
                    catch(SocketTimeoutException e){continue;}
                    byte[] payload=java.util.Arrays.copyOf(packet.getData(),packet.getLength());
                    codec.parse(payload,packet.getAddress(),infoHash,cookie).ifPresent(candidate -> {
                        String key=candidate.getAddress().getHostAddress()+":"+candidate.getPort();
                        if(known.add(key))peers.add(candidate);
                    });
                }
            } finally {
                socket.leaveGroup(new InetSocketAddress(group,LsdAnnouncementCodec.MULTICAST_PORT),networkInterface);
            }
        }
        return List.copyOf(peers);
    }
}
