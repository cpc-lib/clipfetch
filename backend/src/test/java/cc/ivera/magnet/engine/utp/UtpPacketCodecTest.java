package cc.ivera.magnet.engine.utp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class UtpPacketCodecTest {
    final UtpPacketCodec wire=new UtpPacketCodec();
    @Test void roundTripSynDataAndSelectiveAck() throws Exception {
        var syn=new UtpPacketCodec.Packet(UtpPacketCodec.SYN,65535,0xffffffffL,
                0,64*1024,1,0,new byte[0],new byte[0]);
        var synBack=wire.decode(wire.encode(syn));
        assertEquals(syn.type(),synBack.type());
        assertEquals(syn.connectionId(),synBack.connectionId());
        assertEquals(syn.timestampMicros(),synBack.timestampMicros());
        assertEquals(syn.receiveWindow(),synBack.receiveWindow());
        assertArrayEquals(syn.payload(),synBack.payload());
        var sack=new byte[]{(byte)0x09,0,0,0};
        var data=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,17,102030,1200,65536,
                4,65534,sack,new byte[]{42,43});
        var parsed=wire.decode(wire.encode(data));
        assertEquals(data.type(),parsed.type());
        assertArrayEquals(data.payload(),parsed.payload());
        assertArrayEquals(sack,parsed.selectiveAck());
        assertTrue(UtpPacketCodec.selectivelyAcknowledges(sack,65534,0));
        assertTrue(UtpPacketCodec.selectivelyAcknowledges(sack,65534,3));
        assertFalse(UtpPacketCodec.selectivelyAcknowledges(sack,65534,1));
        byte[] leaked=parsed.payload();leaked[0]=99;
        assertEquals(42,parsed.payload()[0]);
    }
    @Test void rejectsInvalidDatagramLengthsAndExtensionChains() throws Exception {
        assertThrows(IOException.class,()->wire.decode(new byte[19]));
        assertThrows(IOException.class,()->wire.decode(new byte[1501]));
        byte[] invalid=wire.encode(new UtpPacketCodec.Packet(
                UtpPacketCodec.STATE,1,1,0,64,2,3,new byte[0],new byte[0]));
        invalid[0]=(byte)0x25; // invalid version nibble
        assertThrows(IOException.class,()->wire.decode(invalid));
        invalid[0]=(byte)0x21;
        invalid[1]=1; // missing SACK bytes
        assertThrows(IOException.class,()->wire.decode(invalid));
        assertThrows(IllegalArgumentException.class,()->new UtpPacketCodec.Packet(
                UtpPacketCodec.DATA,1,1,0,1024,1,1,new byte[0],new byte[0]));
    }
    @Test void congestedDelayOrLossShrinksWindowAndRespectsRemoteZeroWindow() {
        var sender=new UtpCongestionWindow();
        assertTrue(sender.reserve(1200));
        long initial=sender.capacityBytes();
        sender.acknowledge(1200,2000,24000,1_000_000_000L);
        for(int i=0;i<100;i++)sender.acknowledge(1200,3000,18000,1_000_000_000L+i);
        assertTrue(sender.capacityBytes()>initial);
        long grown=sender.capacityBytes();
        sender.acknowledge(1200,500000,25000,2_000_000_000L);
        assertTrue(sender.capacityBytes()<grown);
        sender.lost(0);
        assertTrue(sender.capacityBytes()<=grown/2+1);
        sender.peerWindow(0);
        assertFalse(sender.reserve(100));
        assertThrows(IllegalArgumentException.class,()->sender.reserve(1201));
    }
}
