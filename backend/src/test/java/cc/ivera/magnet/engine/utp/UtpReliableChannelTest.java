package cc.ivera.magnet.engine.utp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UtpReliableChannelTest {
    private static UtpReliableChannel channel(int sendSeq,int receivedAck){
        return new UtpReliableChannel(71,sendSeq,receivedAck,new UtpCongestionWindow());
    }
    @Test void outOfOrderSackAndCumulativeAckAcrossSequenceWrap() throws Exception {
        long start=1_000_000_000L;
        var sender=channel(65535,99);
        var receiver=channel(100,65534);
        var first=sender.send(new byte[]{1},start).orElseThrow();
        var second=sender.send(new byte[]{2},start+1_000).orElseThrow();
        assertEquals(65535,first.seqNr());
        assertEquals(0,second.seqNr());
        assertTrue(receiver.receive(second,start+50_000).isEmpty());
        assertEquals(1,receiver.reorderedPackets());
        var sack=receiver.acknowledgement(start+60_000);
        assertEquals(65534,sack.ackNr());
        assertTrue(UtpPacketCodec.selectivelyAcknowledges(
                sack.selectiveAck(),sack.ackNr(),second.seqNr()));
        sender.receive(sack,start+90_000);
        assertEquals(1,sender.outstandingPackets(),
                "SACK removes only the out-of-order packet");
        var chunks=receiver.receive(first,start+100_000);
        assertEquals(2,chunks.size());
        assertArrayEquals(new byte[]{1},chunks.get(0));
        assertArrayEquals(new byte[]{2},chunks.get(1));
        assertEquals(0,receiver.reorderedPackets());
        sender.receive(receiver.acknowledgement(start+110_000),start+120_000);
        assertEquals(0,sender.outstandingPackets());
        assertEquals(0,sender.retransmit(start+2_000_000_000L).size());
    }
    @Test void timedOutPayloadRetransmitsSameSequenceThenAcknowledgesOnce() throws Exception {
        long start=10_000_000_000L;
        var sender=channel(10,0);
        var receiver=channel(20,9);
        var payload=new byte[1000];
        Arrays.fill(payload,(byte)0x5a);
        var issued=sender.send(payload,start).orElseThrow();
        assertEquals(1,sender.outstandingPackets());
        assertTrue(sender.retransmit(start+900_000_000L).isEmpty());
        var retry=sender.retransmit(start+1_100_000_000L);
        assertEquals(1,retry.size());
        assertEquals(issued.seqNr(),retry.get(0).seqNr());
        assertArrayEquals(payload,retry.get(0).payload());
        assertEquals(1,receiver.receive(retry.get(0),start+1_200_000_000L).size());
        var ack=receiver.acknowledgement(start+1_300_000_000L);
        sender.receive(ack,start+1_400_000_000L);
        assertEquals(0,sender.outstandingPackets());
        assertTrue(sender.retransmit(start+3_000_000_000L).isEmpty());
    }
    @Test void rejectsPacketFromWrongConnectionAndKeepsBoundedReorderWindow() throws Exception {
        var receiver=channel(10,0);
        var wrong=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,72,0,0,1024,
                1,0,new byte[0],new byte[]{1});
        assertThrows(IOException.class,()->receiver.receive(wrong,100));
        for(int i=2;i<200;i++) {
            var p=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,71,0,0,1024,
                    i,0,new byte[0],new byte[1200]);
            receiver.receive(p,200+i);
        }
        assertTrue(receiver.reorderedPackets()<=64);
        assertTrue(receiver.acknowledgement(900).receiveWindow()>=0);
    }
    @Test void refusesForgedFutureCumulativeAcks() throws Exception {
        var sender=channel(10,0);
        sender.send(new byte[]{7},1_000_000L).orElseThrow();
        var forged=new UtpPacketCodec.Packet(UtpPacketCodec.STATE,71,0,0,2048,
                1,100,new byte[0],new byte[0]);
        sender.receive(forged,2_000_000L);
        assertEquals(1,sender.outstandingPackets(),
                "Cannot acknowledge sequence 100 when latest sent was 10");
        var real=new UtpPacketCodec.Packet(UtpPacketCodec.STATE,71,0,0,2048,
                1,10,new byte[0],new byte[0]);
        sender.receive(real,3_000_000L);
        assertEquals(0,sender.outstandingPackets());
    }

    @Test void orderedFinClosesReceiveDirectionAndResetStopsChannel() throws Exception {
        var receiver=channel(10,50);
        var next=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,71,0,0,1024,
                51,0,new byte[0],new byte[]{4});
        var fin=new UtpPacketCodec.Packet(UtpPacketCodec.FIN,71,0,0,1024,
                52,0,new byte[0],new byte[0]);
        assertTrue(receiver.receive(fin,100).isEmpty());
        assertFalse(receiver.remoteFinished());
        assertEquals(1,receiver.receive(next,101).size());
        assertTrue(receiver.remoteFinished());
        var reset=new UtpPacketCodec.Packet(UtpPacketCodec.RESET,71,0,0,0,
                53,0,new byte[0],new byte[0]);
        assertThrows(IOException.class,()->receiver.receive(reset,102));
        assertThrows(IOException.class,()->receiver.acknowledgement(103));
    }
}
