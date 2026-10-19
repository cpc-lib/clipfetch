package cc.ivera.magnet.engine.utp;

/**
 * Bounded LEDBAT-style admission state for BEP29. It models datagram flight
 * windows but does not implement packet retransmission or UDP sockets.
 * Each actor owns one controller and must use it only after uTP handshake.
 */
public final class UtpCongestionWindow {
    private static final int MSS=1200;
    private static final long MAX_WINDOW=4L*1024*1024;
    private static final long TARGET_MICROS=100_000;
    private long maximum=2L*MSS;
    private long inFlight;
    private long advertised=64L*1024;
    private long baseDelay=Long.MAX_VALUE;
    private long baseObservedNanos;
    private long smoothedRttMicros;
    public synchronized long capacityBytes(){return Math.min(maximum,advertised);}
    public synchronized long outstandingBytes(){return inFlight;}
    public synchronized long rttMicros(){return smoothedRttMicros;}
    public synchronized boolean reserve(int bytes) {
        if(bytes<=0 || bytes>MSS)throw new IllegalArgumentException("uTP MTU exceeded");
        if(inFlight+bytes>capacityBytes())return false;
        inFlight+=bytes;
        return true;
    }
    public synchronized void peerWindow(long bytes) {
        if(bytes<0 || bytes>0xffffffffL)
            throw new IllegalArgumentException("Invalid uTP receive window");
        advertised=bytes;
    }
    /** Adjust target congestion window from one-way delay feedback and ACKed bytes. */
    public synchronized void acknowledge(int bytes,long delayMicros,long rttMicros,long nowNanos) {
        if(bytes<0 || delayMicros<0 || rttMicros<0 || nowNanos<0)
            throw new IllegalArgumentException("Invalid uTP ACK feedback");
        inFlight=Math.max(0,inFlight-bytes);
        if(bytes==0)return;
        if(baseDelay==Long.MAX_VALUE ||
                nowNanos-baseObservedNanos>120_000_000_000L) {
            baseDelay=delayMicros;
            baseObservedNanos=nowNanos;
        }else baseDelay=Math.min(baseDelay,delayMicros);
        // Karn's algorithm: zero means ambiguous RTT after retransmission,
        // so adjust the congestion window but preserve the prior RTT sample.
        if(rttMicros>0)smoothedRttMicros=smoothedRttMicros==0?rttMicros:
                (7*smoothedRttMicros+rttMicros)/8;
        long queuing=Math.max(0,delayMicros-baseDelay);
        double offTarget=(TARGET_MICROS-(double)queuing)/TARGET_MICROS;
        long change=Math.round(3000.0*offTarget*(bytes/(double)Math.max(MSS,maximum)));
        if(offTarget>0)maximum=Math.min(MAX_WINDOW,Math.max(MSS,maximum+Math.max(1,change)));
        else maximum=Math.max(MSS,maximum+Math.min(-1,change));
    }
    /** BEP29 congestion response: halve sending capacity after packet loss. */
    public synchronized void lost(int bytes) {
        if(bytes<0)throw new IllegalArgumentException("Invalid lost uTP payload");
        inFlight=Math.max(0,inFlight-bytes);
        maximum=Math.max(MSS,maximum/2);
    }
}
