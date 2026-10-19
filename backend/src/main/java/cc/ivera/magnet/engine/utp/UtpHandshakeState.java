package cc.ivera.magnet.engine.utp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * BEP29 SYN/STATE connection establishment, with distinct receive/send IDs.
 * This machine does not bind UDP or expose a BT stream to Piece workers.
 */
public final class UtpHandshakeState {
    public enum Phase { SYN_SENT,SYN_RECEIVED,CONNECTED,CLOSED }
    public enum Role { INITIATOR,ACCEPTOR }
    private final Role role;
    private final InetSocketAddress endpoint;
    private final int receiveId,sendId;
    private final int ownInitialSeq;
    private final int remoteSynSeq;
    private Phase phase;
    private int remoteInitialSeq=-1;
    private UtpHandshakeState(Role role,InetSocketAddress endpoint,
                              int recv,int send,int ownSeq,int peerSyn,Phase phase) {
        this.role=role;this.endpoint=endpoint;this.receiveId=recv;
        this.sendId=send;this.ownInitialSeq=ownSeq;this.remoteSynSeq=peerSyn;this.phase=phase;
    }
    private static void checkEndpoint(InetSocketAddress endpoint) {
        if(endpoint==null || endpoint.getAddress()==null || endpoint.getPort()<1)
            throw new IllegalArgumentException("Pinned remote endpoint required");
    }
    private static long micros(long nanos) {
        if(nanos<0)throw new IllegalArgumentException("Negative timestamp");
        return (nanos/1000)&0xffffffffL;
    }
    private static UtpPacketCodec.Packet frame(int type,int id,int seq,int ack,long nanos) {
        return new UtpPacketCodec.Packet(type,id,micros(nanos),0,64*1024,
                seq,ack,new byte[0],new byte[0]);
    }
    public record Start(UtpHandshakeState state,UtpPacketCodec.Packet initialPacket) {}
    /** Initiator SYN uses receive connection ID; later DATA uses that ID+1. */
    public static Start initiate(InetSocketAddress remote,int receiveId,long nowNanos) {
        checkEndpoint(remote);
        if(receiveId<0 || receiveId>65535)
            throw new IllegalArgumentException("Invalid receive ID");
        var state=new UtpHandshakeState(Role.INITIATOR,remote,receiveId,
                (receiveId+1)&0xffff,1,-1,Phase.SYN_SENT);
        return new Start(state,frame(UtpPacketCodec.SYN,receiveId,1,0,nowNanos));
    }
    /** Acceptor returns STATE with SYN's ID, while receiving subsequent DATA on ID+1. */
    public static Start accept(InetSocketAddress remote,UtpPacketCodec.Packet syn,
                               int localSequence,long nowNanos) throws IOException {
        checkEndpoint(remote);
        if(syn==null || syn.type()!=UtpPacketCodec.SYN ||
                localSequence<1 || localSequence>65535 || syn.seqNr()<1)
            throw new IOException("Invalid uTP SYN");
        var state=new UtpHandshakeState(Role.ACCEPTOR,remote,
                (syn.connectionId()+1)&0xffff,syn.connectionId(),
                localSequence,syn.seqNr(),Phase.SYN_RECEIVED);
        return new Start(state,frame(UtpPacketCodec.STATE,syn.connectionId(),
                localSequence,syn.seqNr(),nowNanos));
    }
    /** Source validation belongs to a pinned address+port UDP demultiplexer. */
    public synchronized void onInbound(InetSocketAddress source,UtpPacketCodec.Packet packet)
            throws IOException {
        if(phase==Phase.CLOSED || !endpoint.equals(source) || packet==null ||
                packet.connectionId()!=receiveId)
            throw new IOException("Unknown or closed uTP session");
        if(role==Role.INITIATOR && phase==Phase.SYN_SENT) {
            if(packet.type()!=UtpPacketCodec.STATE || packet.ackNr()!=1)
                throw new IOException("Invalid SYN acknowledgement");
            remoteInitialSeq=packet.seqNr();
            phase=Phase.CONNECTED;
        }else if(role==Role.ACCEPTOR && phase==Phase.SYN_RECEIVED) {
            if((packet.type()!=UtpPacketCodec.DATA &&
                    packet.type()!=UtpPacketCodec.STATE) ||
                    packet.ackNr()!=ownInitialSeq ||
                    packet.seqNr()!=((remoteSynSeq+1)&0xffff))
                throw new IOException("Unexpected post-SYN packet");
            remoteInitialSeq=packet.seqNr();
            phase=Phase.CONNECTED;
        }else if(packet.type()==UtpPacketCodec.RESET) {
            phase=Phase.CLOSED;
        }else if(phase!=Phase.CONNECTED) {
            throw new IOException("Unexpected uTP session phase");
        }
    }
    public synchronized void close(){phase=Phase.CLOSED;}
    public synchronized Phase phase(){return phase;}
    public Role role(){return role;}
    public InetSocketAddress endpoint(){return endpoint;}
    public int receiveId(){return receiveId;}
    public int sendId(){return sendId;}
    public int nextDataSequence(){return (ownInitialSeq+1)&0xffff;}
    public synchronized int nextExpectedReceiveSequence() {
        return remoteInitialSeq<0?-1:(remoteInitialSeq+1)&0xffff;
    }
}
