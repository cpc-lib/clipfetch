package cc.ivera.magnet.engine.piece;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local, task-isolated BEP3 request-queue telemetry.
 * One actor thread owns each gauge. No raw endpoint or user data leaves
 * the task scope. RTT percentiles use a bounded log2 histogram.
 */
public final class NativePeerRequestTelemetry {
    public record Snapshot(long sentBlocks,long ackedBlocks,int outstandingBlocks,
                    int capacityBlocks,int occupancyPercent,
                    long rttP50Millis,long rttP95Millis) {}
    private record Queue(int pending,int capacity) {}
    private final ConcurrentHashMap<Thread,Queue> active=new ConcurrentHashMap<>();
    private final LongAdder sent=new LongAdder(),acked=new LongAdder();
    private final AtomicLongArray rttHistogram=new AtomicLongArray(16);

    void observe(Thread actor,int outstanding,int capacity,int newlySent,
                 boolean acknowledged,long sampleRttNanos) {
        if(actor==null || outstanding<0 || capacity<0 || outstanding>capacity ||
                newlySent<0)throw new IllegalArgumentException("Invalid Peer pipeline sample");
        active.put(actor,new Queue(outstanding,capacity));
        if(newlySent>0)sent.add(newlySent);
        if(acknowledged){
            acked.increment();
            if(sampleRttNanos>0) {
                long millis=Math.max(1,sampleRttNanos/1_000_000L);
                int slot=0;
                while(slot<15 && millis>(1L<<slot))slot++;
                rttHistogram.incrementAndGet(slot);
            }
        }
    }
    void leave(Thread actor) {if(actor!=null)active.remove(actor);}
    Snapshot snapshot(){
        int pending=0,capacity=0;
        for(var queue:active.values()){
            pending+=queue.pending();
            capacity+=queue.capacity();
        }
        int occupancy=capacity==0?0:(int)Math.min(100,100L*pending/capacity);
        long count=0;
        for(int i=0;i<16;i++)count+=rttHistogram.get(i);
        return new Snapshot(sent.sum(),acked.sum(),pending,capacity,occupancy,
                percentile(count,50),percentile(count,95));
    }
    private long percentile(long samples,int percent) {
        if(samples==0)return 0;
        long target=(samples*percent+99)/100,cumulative=0;
        for(int i=0;i<16;i++) {
            cumulative+=rttHistogram.get(i);
            if(cumulative>=target)return 1L<<i;
        }
        return 1L<<15;
    }
}
