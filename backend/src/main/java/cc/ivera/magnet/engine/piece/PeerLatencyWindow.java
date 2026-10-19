package cc.ivera.magnet.engine.piece;

import java.util.Arrays;

/** Bounded 32-sample, task-and-Peer-local application Block RTT tail. */
final class PeerLatencyWindow {
    private static final int CAP=32;
    private final long[] nanos=new long[CAP];
    private int size,next;
    synchronized void add(long sample) {
        if(sample<=0)return;
        nanos[next]=sample;
        next=(next+1)%CAP;
        if(size<CAP)size++;
    }
    synchronized long p95Nanos() {
        if(size==0)return 0;
        long[] values=Arrays.copyOf(nanos,size);
        Arrays.sort(values);
        return values[(int)Math.ceil(0.95*size)-1];
    }
    synchronized int count() {return size;}
}
