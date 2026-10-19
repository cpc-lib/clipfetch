package cc.ivera.magnet.engine.piece;

/**
 * Per-owner-task AIMD network admission controller. An unverified, cooling,
 * or unavailable Peer never increases the runnable window. This controller
 * never owns sockets, Piece data or durable task state.
 */
final class AdaptivePeerConcurrency {
    static final int INITIAL_WINDOW=8;
    // Keep a bounded exploration floor. Otherwise repeated failed batches
    // reduce 8 -> 4 -> 2 -> 1, preventing discovery of healthy Piece peers.
    static final int EXPLORATION_FLOOR=8;
    private int target;

    AdaptivePeerConcurrency() { target=INITIAL_WINDOW; }
    AdaptivePeerConcurrency(AdaptivePeerConcurrency previous) {
        target=previous==null?INITIAL_WINDOW:
                Math.max(EXPLORATION_FLOOR,previous.currentTarget());
    }

    synchronized int currentTarget() { return target; }

    synchronized int slots(int maxConfigured,int eligiblePeers,
                           int wantedPieces,int memorySlots) {
        if(maxConfigured<1 || eligiblePeers<0 || wantedPieces<0 || memorySlots<1)
            throw new IllegalArgumentException("Invalid adaptive admission inputs");
        // Keep one slot for bounded Peer rediscovery when every candidate cooled.
        return Math.max(1,Math.min(Math.min(Math.max(EXPLORATION_FLOOR,target),maxConfigured),
                Math.min(Math.max(1,eligiblePeers),Math.min(wantedPieces,memorySlots))));
    }

    synchronized void observe(int attempted,int verified) {
        if(attempted<0 || verified<0 || verified>attempted)
            throw new IllegalArgumentException("Invalid adaptive round outcome");
        if(attempted==0)return;
        if(verified==0 || verified*4<attempted) {
            target=Math.max(EXPLORATION_FLOOR,target/2);
        }else if(verified*2>=attempted) {
            target=Math.min(PieceDownloadCoordinator.MAX_PARALLEL_PIECES,target*2);
        }
        // Otherwise hold rather than oscillating due to a few slow Peers.
    }
}
