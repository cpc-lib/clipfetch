package cc.ivera.magnet.engine.piece;

/**
 * One authenticated Peer actor's bounded, block-centric request pipeline.
 * Uses observed Block RTT and received payload rate rather than a fixed batch.
 * Actor-confined; never shares the request budget across users or torrents.
 */
final class PeerBlockPipelineController {
    private static final int INITIAL=16;
    private static final int DEFAULT_MAX=PeerBlockMailbox.DEFAULT_MAX_PEER_IN_FLIGHT;
    private final int max;
    private long lastDeliveredNanos;
    private double rateBytesPerSecond;
    private long rttNanos;
    private int acknowledged;

    PeerBlockPipelineController() {this(DEFAULT_MAX);}
    PeerBlockPipelineController(int max) {
        if(max<INITIAL || max>PeerBlockMailbox.MAX_ALLOWED_PEER_IN_FLIGHT)
            throw new IllegalArgumentException("Invalid Peer pipeline window");
        this.max=max;
    }

    int target() {
        if(acknowledged<INITIAL || rateBytesPerSecond<=0)return INITIAL;
        // Keep ~2 RTTs of useful work queued. Public swarms routinely show
        // 4-8s Block RTT; clamping below 2 RTTs would pin every slow Peer
        // at the floor and cap it near floor*BLOCK/RTT (~32 KiB/s at 4s).
        double seconds=Math.max(0.25,Math.min(12.0,
                2.0*rttNanos/1_000_000_000.0));
        int desired=(int)Math.ceil(rateBytesPerSecond*seconds/
                PieceBlockPlanner.BLOCK_BYTES)+2;
        return Math.max(8,Math.min(max,desired));
    }

    int nextBatch(int outstanding) {
        if(outstanding<0 || outstanding>max)
            throw new IllegalArgumentException("Invalid outstanding Block count");
        return Math.min(Math.min(32,max),Math.max(0,target()-outstanding));
    }

    void received(int bytes,long sampledRttNanos,long nowNanos) {
        if(bytes<1 || bytes>PieceBlockPlanner.BLOCK_BYTES ||
                sampledRttNanos<0 || nowNanos<0)
            throw new IllegalArgumentException("Invalid Block throughput sample");
        if(sampledRttNanos>0)
            rttNanos=rttNanos==0?sampledRttNanos:
                    (7*rttNanos+sampledRttNanos)/8;
        if(lastDeliveredNanos>0 && nowNanos>lastDeliveredNanos) {
            double seconds=(nowNanos-lastDeliveredNanos)/1_000_000_000.0;
            double instant=bytes/Math.max(0.0001,seconds);
            rateBytesPerSecond=rateBytesPerSecond==0?instant:
                    0.75*rateBytesPerSecond+0.25*instant;
        }
        lastDeliveredNanos=nowNanos;
        acknowledged++;
    }

    int acknowledged(){return acknowledged;}
    long rttNanos(){return rttNanos;}
}
