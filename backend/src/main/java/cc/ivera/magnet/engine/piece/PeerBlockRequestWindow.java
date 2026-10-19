package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded BEP3 block request pipeline for a single Peer and Piece.
 * Peer messages may arrive out of order; duplicates, unsolicited and
 * malformed offsets never contribute to verified Piece data.
 */
final class PeerBlockRequestWindow {
    // 256KiB Piece = 16 requests. Prime the pipeline in one RTT;
    // for larger Pieces retain a hard 1MiB bound per Peer (64 * 16KiB).
    static final int INITIAL=16;
    static final int MAX=64;

    private final List<PieceBlockPlanner.Block> blocks;
    private final Map<Integer,Integer> slotsByBegin;
    private final BitSet outstanding;
    private final BitSet received;
    private int next;
    private int pending;
    private int window=INITIAL;
    private int acknowledged;
    private final long[] sentNanos;
    private final LongSupplier nanoClock;
    private long rttEwmaNanos;
    private long lastObservedRttNanos;
    private long receivedBytes;
    private long sampleStartedNanos;
    private long recentBytes;
    private long lastRateNanos;
    private double rateEwmaBytesPerSecond;

    PeerBlockRequestWindow(List<PieceBlockPlanner.Block> blocks) {
        this(blocks,System::nanoTime);
    }
    PeerBlockRequestWindow(List<PieceBlockPlanner.Block> blocks,LongSupplier clock) {
        if(clock==null)throw new IllegalArgumentException("Missing monotonic clock");
        nanoClock=clock;
        if(blocks==null || blocks.isEmpty())
            throw new IllegalArgumentException("No requested blocks");
        this.blocks=List.copyOf(blocks);
        this.slotsByBegin=new HashMap<>();
        for(int i=0;i<blocks.size();i++) {
            var block=blocks.get(i);
            if(block.begin()%PieceBlockPlanner.BLOCK_BYTES!=0 ||
                    slotsByBegin.putIfAbsent(block.begin(),i)!=null)
                throw new IllegalArgumentException("Invalid sparse block request");
        }
        this.outstanding=new BitSet(blocks.size());
        this.received=new BitSet(blocks.size());
        this.sentNanos=new long[blocks.size()];
    }

    List<PieceBlockPlanner.Block> fill() {
        List<PieceBlockPlanner.Block> requested=new ArrayList<>();
        int desired=targetOutstanding();
        while(next<blocks.size() && pending<desired) {
            requested.add(blocks.get(next));
            outstanding.set(next);
            sentNanos[next]=nanoClock.getAsLong();
            if(sampleStartedNanos==0)sampleStartedNanos=sentNanos[next];
            next++;
            pending++;
        }
        return requested;
    }

    void acknowledge(int piece,int receivedPiece,int begin,int length) throws IOException {
        if(receivedPiece!=piece || begin<0 || begin%PieceBlockPlanner.BLOCK_BYTES!=0)
            throw new IOException("Unexpected Peer Piece block index or offset");
        Integer slot=slotsByBegin.get(begin);
        if(slot==null || !outstanding.get(slot) || received.get(slot) ||
                blocks.get(slot).begin()!=begin || blocks.get(slot).length()!=length)
            throw new IOException("Unsolicited, duplicate or malformed Peer Piece block");
        long observed=Math.max(1,nanoClock.getAsLong()-sentNanos[slot]);
        lastObservedRttNanos=observed;
        rttEwmaNanos=rttEwmaNanos==0?observed:
                (7*rttEwmaNanos+observed)/8;
        receivedBytes+=length;
        recentBytes+=length;
        long sampleNow=nanoClock.getAsLong();
        if(lastRateNanos==0)lastRateNanos=sampleStartedNanos;
        long period=Math.max(0,sampleNow-lastRateNanos);
        if(period>=100_000_000L) {
            double instantaneous=recentBytes*1_000_000_000.0/period;
            rateEwmaBytesPerSecond=rateEwmaBytesPerSecond==0?instantaneous:
                    0.75*rateEwmaBytesPerSecond+0.25*instantaneous;
            recentBytes=0;
            lastRateNanos=sampleNow;
        }
        received.set(slot);
        outstanding.clear(slot);
        pending--;
        acknowledged++;
        // Application-level slow start, analogous to libtorrent's
        // expanding request queue; never exceed MAX outstanding blocks.
        if(acknowledged%8==0 && window<MAX)
            window=Math.min(MAX,window+8);
    }

    /** RTT-aware, bounded pending-request deadline. A slow but noisy Peer
     * cannot keep 16KiB outstanding forever just by sending HAVE/KEEPALIVE.
     * The caller closes the stalled transport and retries missing blocks on
     * another authenticated Peer. This is not a persistent Block timeout.
     */
    long nextDeadlineNanos() {
        if(pending==0)return Long.MAX_VALUE;
        long timeout=rttEwmaNanos==0?4_000_000_000L:
                Math.max(2_000_000_000L,Math.min(8_000_000_000L,rttEwmaNanos*4));
        long earliest=Long.MAX_VALUE;
        for(int index=outstanding.nextSetBit(0);index>=0;index=outstanding.nextSetBit(index+1))
            earliest=Math.min(earliest,sentNanos[index]+timeout);
        return earliest;
    }
    long estimatedRttNanos() {return rttEwmaNanos;}
    long lastObservedRttNanos() {return lastObservedRttNanos;}
    long sampledPayloadBytes() {return receivedBytes;}
    /** Dynamic request queue target from recent throughput, clamped by the
     * current slow-start cap, like libtorrent's request_queue_time.
     */
    int targetOutstanding() {
        if(acknowledged<4 || rttEwmaNanos==0 || sampleStartedNanos==0)return window;
        // libtorrent-style request_queue_time: keep enough outstanding work
        // for ~2 RTTs, with a lower bound preventing a fast Peer from
        // draining its pipeline during the first few small ACK samples.
        double seconds=Math.max(0.25,Math.min(2.0,
                2.0*rttEwmaNanos/1_000_000_000.0));
        double bytesPerSecond=rateEwmaBytesPerSecond;
        if(bytesPerSecond<=0) {
            long elapsed=Math.max(1,nanoClock.getAsLong()-sampleStartedNanos);
            bytesPerSecond=receivedBytes*1_000_000_000.0/elapsed;
        }
        int desired=(int)Math.ceil(bytesPerSecond*seconds/
                PieceBlockPlanner.BLOCK_BYTES);
        // Do not collapse a healthy connection's initial 16-request pipe
        // to four after its first 4 ACKs. Continue priming throughout the
        // first 16 replies; afterwards keep a smaller floor for slow Peers.
        int floor=acknowledged<INITIAL?INITIAL:8;
        return Math.min(window,Math.max(floor,desired));
    }

    /** Snapshot of outstanding Block leases for protocol CANCEL on preemption.
     * Already received Blocks are never cancelled or resubmitted.
     */
    List<PieceBlockPlanner.Block> pendingBlocks() {
        var result=new ArrayList<PieceBlockPlanner.Block>();
        for(int i=outstanding.nextSetBit(0);i>=0;i=outstanding.nextSetBit(i+1))
            result.add(blocks.get(i));
        return List.copyOf(result);
    }
    boolean complete() {return acknowledged==blocks.size();}
    int inFlight() {return pending;}
    int window() {return window;}
}
