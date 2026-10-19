package cc.ivera.magnet.engine.utp;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** No public listener: two ephemeral UDP sockets exchange genuine loopback datagrams. */
class UtpLoopbackDatagramTest {
    @Test void packetLossRetransmissionAndAckOverRealLoopbackUdp() throws Exception {
        var loopback=InetAddress.getLoopbackAddress();
        var codec=new UtpPacketCodec();
        var sender=new UtpReliableChannel(900,1,200,new UtpCongestionWindow());
        var receiver=new UtpReliableChannel(900,201,0,new UtpCongestionWindow());
        try(var out=new DatagramSocket(new InetSocketAddress(loopback,0));
            var in=new DatagramSocket(new InetSocketAddress(loopback,0))) {
            out.setSoTimeout(1000);
            in.setSoTimeout(1000);
            long now=1_000_000_000L;
            byte[] expected=new byte[1000];
            Arrays.fill(expected,(byte)0x25);
            var first=sender.send(expected,now).orElseThrow();
            send(out,in.getLocalSocketAddress(),codec.encode(first));
            var accepted=receiver.receive(codec.decode(read(in)),now+20_000_000L);
            assertEquals(1,accepted.size());
            assertArrayEquals(expected,accepted.get(0));
            send(in,out.getLocalSocketAddress(),codec.encode(receiver.acknowledgement(now+21_000_000L)));
            sender.receive(codec.decode(read(out)),now+30_000_000L);
            assertEquals(0,sender.outstandingPackets());

            // The next DATA datagram is intentionally dropped to model UDP loss.
            var lost=sender.send(new byte[]{42},now+100_000_000L).orElseThrow();
            assertEquals(2,lost.seqNr());
            assertTrue(sender.retransmit(now+250_000_000L).isEmpty());
            var retries=sender.retransmit(now+1_200_000_000L);
            assertEquals(1,retries.size());
            assertEquals(lost.seqNr(),retries.get(0).seqNr());
            send(out,in.getLocalSocketAddress(),codec.encode(retries.get(0)));
            var retried=receiver.receive(codec.decode(read(in)),now+1_300_000_000L);
            assertEquals(1,retried.size());
            assertArrayEquals(new byte[]{42},retried.get(0));
            send(in,out.getLocalSocketAddress(),
                    codec.encode(receiver.acknowledgement(now+1_301_000_000L)));
            sender.receive(codec.decode(read(out)),now+1_302_000_000L);
            assertEquals(0,sender.outstandingPackets());
            assertTrue(sender.retransmit(now+3_000_000_000L).isEmpty());
        }
    }
    private static void send(DatagramSocket from,SocketAddress to,byte[] payload)
            throws Exception {
        from.send(new DatagramPacket(payload,payload.length,to));
    }
    private static byte[] read(DatagramSocket socket) throws Exception {
        var bytes=new byte[UtpPacketCodec.MAX_DATAGRAM];
        var packet=new DatagramPacket(bytes,bytes.length);
        socket.receive(packet);
        return Arrays.copyOf(packet.getData(),packet.getLength());
    }
}
