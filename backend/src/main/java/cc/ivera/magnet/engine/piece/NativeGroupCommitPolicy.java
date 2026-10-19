package cc.ivera.magnet.engine.piece;

import java.util.concurrent.TimeUnit;

/**
 * Owner-thread-only bounded durable progress publication policy.
 * A group is published as a whole, never by individual network actors.
 * Limits avoid unbounded SHA-1-verified, fsynced but uncredited data.
 */
final class NativeGroupCommitPolicy {
    static final int MAX_UNPUBLISHED_BYTES=16*1024*1024;
    static final long MAX_UNPUBLISHED_NANOS=TimeUnit.SECONDS.toNanos(2);
    private NativeGroupCommitPolicy() {}

    static boolean shouldPublish(int pendingPieces,int configuredPieces,long pendingBytes,
                                 long firstStagedNanos,long nowNanos,boolean finalPiece) {
        if(pendingPieces<0 || configuredPieces<1 || pendingBytes<0)
            throw new IllegalArgumentException("Invalid durable group budget");
        if(pendingPieces==0)return false;
        long maxBytes=Math.max(MAX_UNPUBLISHED_BYTES,configuredPieces*256L*1024L);
        return finalPiece || pendingPieces>=configuredPieces ||
                pendingBytes>=maxBytes ||
                firstStagedNanos>0 && nowNanos-firstStagedNanos>=MAX_UNPUBLISHED_NANOS;
    }

    /** Poll only long enough to honor the publication deadline. */
    static long maxPollMillis(int pendingPieces,long firstStagedNanos,long nowNanos) {
        if(pendingPieces<=0)return 1000;
        if(firstStagedNanos==0)return 200;
        long remaining=MAX_UNPUBLISHED_NANOS-(nowNanos-firstStagedNanos);
        if(remaining<=0)return 1;
        return Math.max(1,Math.min(200,TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
