package cc.ivera.magnet.engine.piece;

/**
 * Task-local recent network throughput meter. Poll frequency must not make
 * displayed speed drop to zero on repeated UI reads without enough elapsed
 * time. A stale sample decays to zero if the Peer stopped delivering bytes.
 */
final class NativeNetworkRateMeter {
    private static final long MIN_SAMPLE_NANOS=500_000_000L;
    private static final long IDLE_ZERO_NANOS=5_000_000_000L;
    private long lastNanos;
    private long lastBytes;
    private long lastPayloadNanos;
    private double ewma;
    double sample(long bytes,long nowNanos) {
        if(bytes<0 || nowNanos<0)throw new IllegalArgumentException("Invalid network sample");
        if(lastNanos==0) {
            lastNanos=nowNanos;
            lastBytes=bytes;
            lastPayloadNanos=nowNanos;
            return 0;
        }
        if(nowNanos<=lastNanos)return ewma;
        long delta=Math.max(0,bytes-lastBytes);
        if(delta>0)lastPayloadNanos=nowNanos;
        long interval=nowNanos-lastNanos;
        // Show the first real payload immediately, even if UI polls faster
        // than the stable sampling interval; subsequent polls retain EWMA.
        if(delta>0 && ewma==0 && interval<MIN_SAMPLE_NANOS)
            ewma=delta*1_000_000_000.0/Math.max(100_000_000L,interval);
        if(interval>=MIN_SAMPLE_NANOS) {
            double current=delta*1_000_000_000.0/interval;
            ewma=ewma==0?current:0.65*ewma+0.35*current;
            lastNanos=nowNanos;
            lastBytes=Math.max(lastBytes,bytes);
        }
        if(nowNanos-lastPayloadNanos>=IDLE_ZERO_NANOS)ewma=0;
        return ewma;
    }
}
