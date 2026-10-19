package cc.ivera.magnet.engine.utp;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** BEP29 SYN/STATE + per-task demultiplex on real, ephemeral loopback UDP sockets. */
class UtpHandshakeLoopbackTest {
    private final UtpPacketCodec codec=new UtpPacketCodec();
    @Test void actualUdpSynStateAndDataAreScopedToTheRegisteredTask() throws Exception {
        var localhost=InetAddress.getLoopbackAddress();
        try(var a=new DatagramSocket(new InetSocketAddress(localhost,0));
            var b=new DatagramSocket(new InetSocketAddress(localhost,0))) {
            a.setSoTimeout(1500);b.setSoTimeout(1500);
            InetSocketAddress initiatorEndpoint=
                    new InetSocketAddress(localhost,a.getLocalPort());
            InetSocketAddress acceptorEndpoint=
                    new InetSocketAddress(localhost,b.getLocalPort());
            long now=1_000_000_000L;
            int connId=64000;
            var initiated=UtpHandshakeState.initiate(acceptorEndpoint,connId,now);
            send(a,acceptorEndpoint,codec.encode(initiated.initialPacket()));
            var syn=codec.decode(receive(b));
            assertEquals(UtpPacketCodec.SYN,syn.type());
            var accepted=UtpHandshakeState.accept(initiatorEndpoint,syn,1000,now+1_000_000);
            var sessions=new UtpConnectionRegistry();
            var task=UUID.randomUUID();
            sessions.register(31,task,accepted.state());
            send(b,initiatorEndpoint,codec.encode(accepted.initialPacket()));
            var state=codec.decode(receive(a));
            initiated.state().onInbound(acceptorEndpoint,state);
            assertEquals(UtpHandshakeState.Phase.CONNECTED,initiated.state().phase());
            var data=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,
                    initiated.state().sendId(),now/1000+2000,0,65536,
                    initiated.state().nextDataSequence(),1000,new byte[0],new byte[]{10});
            send(a,acceptorEndpoint,codec.encode(data));
            var datagram=codec.decode(receive(b));
            assertThrows(java.io.IOException.class,
                    ()->sessions.route(32,task,initiatorEndpoint,datagram));
            sessions.route(31,task,initiatorEndpoint,datagram);
            assertEquals(UtpHandshakeState.Phase.CONNECTED,accepted.state().phase());
            sessions.removeTask(31,task);
            assertEquals(0,sessions.taskCount(31,task));
        }
    }
    private static void send(DatagramSocket socket,SocketAddress dest,byte[] payload)
            throws Exception {
        socket.send(new DatagramPacket(payload,payload.length,dest));
    }
    private static byte[] receive(DatagramSocket socket) throws Exception {
        var b=new byte[UtpPacketCodec.MAX_DATAGRAM];
        var packet=new DatagramPacket(b,b.length);
        socket.receive(packet);
        return Arrays.copyOf(b,packet.getLength());
    }
}
