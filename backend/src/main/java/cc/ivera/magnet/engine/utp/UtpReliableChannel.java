package cc.ivera.magnet.engine.utp;

import java.io.IOException;
import java.util.*;

/**
 * Task-local BEP29 packet reliability state machine.
 *
 * Owns 16-bit sequence numbers, bounded DATA reordering, ACK/SACK, and
 * retransmission with Karn RTT suppression. Does not open UDP sockets or
 * implement a full uTP handshake/demultiplexer; do not enable as a
 * download transport until those layers pass interoperability tests.
 */
public final class UtpReliableChannel {
    private static final int MAX_UNACKED=128;
    private static final int MAX_REORDERED=64;
    private static final int MAX_REORDER_BYTES=64*1200;
    private static final long MIN_RTO_NANOS=200_000_000L;
    private static final long MAX_RTO_NANOS=8_000_000_000L;
    private static final int MAX_RETRIES=5;
    private final int connectionId;
    private final UtpCongestionWindow window;
    private int sendSequence;
    private int expectedReceive;
    private final LinkedHashMap<Integer,Sent> sent=new LinkedHashMap<>();
    private final Map<Integer,byte[]> outOfOrder=new HashMap<>();
    private final Set<Integer> outOfOrderFin=new HashSet<>();
    private int bufferedBytes;
    private boolean remoteFinished;
    private boolean stopped;
    private static final class Sent {
        final UtpPacketCodec.Packet packet;
        long lastSentNanos;
        int retries;
        Sent(UtpPacketCodec.Packet packet,long now) {
            this.packet=packet;
            lastSentNanos=now;
        }
    }
    public UtpReliableChannel(int connectionId,int initialSendSeq,int initialAck,
                              UtpCongestionWindow window) {
        if(connectionId<0 || connectionId>65535 ||
                initialSendSeq<0 || initialSendSeq>65535 ||
                initialAck<0 || initialAck>65535 || window==null)
            throw new IllegalArgumentException("Invalid uTP state");
        this.connectionId=connectionId;
        this.sendSequence=initialSendSeq;
        this.expectedReceive=(initialAck+1)&0xffff;
        this.window=window;
    }
    private static int delta(int ahead,int behind){return (ahead-behind+65536)&0xffff;}
    private static long timestamp(long nowNanos){return (nowNanos/1000)&0xffffffffL;}
    private static byte[] copy(byte[] data){return data.clone();}
    private void requireActive() throws IOException {
        if(stopped)throw new IOException("uTP channel is stopped");
    }
    public synchronized Optional<UtpPacketCodec.Packet> send(byte[] body,long nowNanos)
            throws IOException {
        requireActive();
        if(body==null || body.length<1 || body.length>1200 || nowNanos<0)
            throw new IllegalArgumentException("Invalid uTP DATA payload");
        if(sent.size()>=MAX_UNACKED || sent.containsKey(sendSequence) ||
                !window.reserve(body.length))
            return Optional.empty();
        int sequence=sendSequence;
        sendSequence=(sendSequence+1)&0xffff;
        var packet=new UtpPacketCodec.Packet(UtpPacketCodec.DATA,connectionId,
                timestamp(nowNanos),0,MAX_REORDER_BYTES-bufferedBytes,sequence,
                (expectedReceive-1+65536)&0xffff,selectiveAck(),body);
        sent.put(sequence,new Sent(packet,nowNanos));
        return Optional.of(packet);
    }
    /**
     * Consume an authenticated connection packet. Caller owns connection-ID
     * demultiplexing and validates the UDP remote endpoint.
     * Returns only ordered payload chunks; never expose gaps to a byte stream.
     */
    public synchronized List<byte[]> receive(UtpPacketCodec.Packet packet,long nowNanos)
            throws IOException {
        requireActive();
        if(packet==null || packet.connectionId()!=connectionId || nowNanos<0)
            throw new IOException("Packet is not part of this uTP connection");
        if(packet.type()==UtpPacketCodec.RESET) {
            stop();
            throw new IOException("Remote reset the uTP connection");
        }
        window.peerWindow(packet.receiveWindow());
        consumeAcks(packet,nowNanos);
        if(packet.type()!=UtpPacketCodec.DATA && packet.type()!=UtpPacketCodec.FIN)
            return List.of();
        int seq=packet.seqNr();
        int distance=delta(seq,expectedReceive);
        if(distance>=MAX_REORDERED && distance<32768)return List.of();
        if(distance>=32768)return List.of(); // old duplicate
        if(remoteFinished)return List.of();
        if(distance>0) {
            if(outOfOrder.size()+outOfOrderFin.size()>=MAX_REORDERED)return List.of();
            if(packet.type()==UtpPacketCodec.FIN)outOfOrderFin.add(seq);
            else if(!outOfOrder.containsKey(seq)) {
                var bytes=packet.payload();
                if(bufferedBytes+bytes.length>MAX_REORDER_BYTES)return List.of();
                outOfOrder.put(seq,bytes);
                bufferedBytes+=bytes.length;
            }
            return List.of();
        }
        var ordered=new ArrayList<byte[]>();
        if(packet.type()==UtpPacketCodec.DATA)ordered.add(packet.payload());
        else remoteFinished=true;
        expectedReceive=(expectedReceive+1)&0xffff;
        while(!remoteFinished) {
            if(outOfOrderFin.remove(expectedReceive)) {
                remoteFinished=true;
                expectedReceive=(expectedReceive+1)&0xffff;
                break;
            }
            byte[] chunk=outOfOrder.remove(expectedReceive);
            if(chunk==null)break;
            bufferedBytes-=chunk.length;
            ordered.add(copy(chunk));
            expectedReceive=(expectedReceive+1)&0xffff;
        }
        return List.copyOf(ordered);
    }
    private void consumeAcks(UtpPacketCodec.Packet packet,long nowNanos) {
        int acknowledged=packet.ackNr();
        // Reject forged/out-of-window ACKs that claim never-issued sequence
        // numbers. With at most 128 in-flight packets, half-range comparison
        // is unambiguous even when sequence numbers wrap at 65535.
        int newestSent=(sendSequence-1+65536)&0xffff;
        if(delta(newestSent,acknowledged)>=32768)return;
        for(var iterator=sent.entrySet().iterator();iterator.hasNext();) {
            var next=iterator.next();
            int seq=next.getKey();
            int behind=delta(acknowledged,seq);
            boolean cumulative=behind<32768;
            boolean selective=UtpPacketCodec.selectivelyAcknowledges(
                    packet.selectiveAck(),acknowledged,seq);
            if(!cumulative && !selective)continue;
            var item=next.getValue();
            int bytes=item.packet.payload().length;
            // Retransmitted samples are ambiguous (Karn's algorithm).
            long roundTrip=item.retries==0?
                    Math.max(0,(nowNanos-item.lastSentNanos)/1000):0;
            window.acknowledge(bytes,packet.timestampDeltaMicros(),roundTrip,nowNanos);
            iterator.remove();
        }
    }
    /** Return timed-out datagrams with original sequence for safe retransmit. */
    public synchronized List<UtpPacketCodec.Packet> retransmit(long nowNanos)
            throws IOException {
        requireActive();
        if(nowNanos<0)throw new IllegalArgumentException("Negative monotonic time");
        long rtt=window.rttMicros();
        long timeout=rtt==0?1_000_000_000L:
                Math.max(MIN_RTO_NANOS,Math.min(MAX_RTO_NANOS,rtt*4000L));
        var retry=new ArrayList<UtpPacketCodec.Packet>();
        for(var item:sent.values()) {
            if(nowNanos-item.lastSentNanos<timeout)continue;
            if(item.retries>=MAX_RETRIES) {
                stop();
                throw new IOException("uTP retransmission limit exceeded");
            }
            int bytes=item.packet.payload().length;
            window.lost(bytes);
            if(!window.reserve(bytes))continue;
            item.retries++;
            item.lastSentNanos=nowNanos;
            var p=item.packet;
            retry.add(new UtpPacketCodec.Packet(p.type(),connectionId,
                    timestamp(nowNanos),0,MAX_REORDER_BYTES-bufferedBytes,
                    p.seqNr(),(expectedReceive-1+65536)&0xffff,selectiveAck(),p.payload()));
            if(retry.size()>=8)break;
        }
        return List.copyOf(retry);
    }
    public synchronized UtpPacketCodec.Packet acknowledgement(long nowNanos) throws IOException {
        requireActive();
        if(nowNanos<0)throw new IllegalArgumentException("Negative monotonic time");
        return new UtpPacketCodec.Packet(UtpPacketCodec.STATE,connectionId,
                timestamp(nowNanos),0,MAX_REORDER_BYTES-bufferedBytes,sendSequence,
                (expectedReceive-1+65536)&0xffff,selectiveAck(),new byte[0]);
    }
    private byte[] selectiveAck() {
        if(outOfOrder.isEmpty() && outOfOrderFin.isEmpty())return new byte[0];
        var bitmap=new byte[4]; // bounded 32 selective positions
        for(int seq:outOfOrder.keySet())setBit(bitmap,seq);
        for(int seq:outOfOrderFin)setBit(bitmap,seq);
        return bitmap;
    }
    private void setBit(byte[] bitmap,int seq) {
        int relative=delta(seq,(expectedReceive-1+65536)&0xffff)-2;
        if(relative>=0 && relative<bitmap.length*8)
            bitmap[relative/8]|=(byte)(1<<(relative%8));
    }
    public synchronized boolean remoteFinished(){return remoteFinished;}
    public synchronized int outstandingPackets(){return sent.size();}
    public synchronized int reorderedPackets(){return outOfOrder.size()+outOfOrderFin.size();}
    public synchronized void stop(){
        stopped=true;sent.clear();outOfOrder.clear();outOfOrderFin.clear();bufferedBytes=0;
    }
}
