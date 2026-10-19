package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Monotonic network deadline for a native work round/continuous session.
 *
 * The immutable deadline is inherited only by newly created Piece network
 * worker threads. Every blocking transport must still enforce its own socket
 * or UDP receive timeout; this is not an OS-level DNS/write hard timeout.
 */
public final class NativeNetworkBudget {
    public static final String DEADLINE_EXCEEDED="Native task network deadline exceeded";
    private static final InheritableThreadLocal<Long> ACTIVE=new InheritableThreadLocal<>();

    private NativeNetworkBudget() {}

    /** True only for this budget's own deadline signal, not socket-level timeouts. */
    public static boolean deadlineExceeded(Throwable failure) {
        return failure instanceof SocketTimeoutException
                && DEADLINE_EXCEEDED.equals(failure.getMessage());
    }

    public static Scope open(Duration limit) {
        if(limit==null || limit.isNegative() || limit.isZero() ||
                limit.compareTo(Duration.ofDays(7))>0)
            throw new IllegalArgumentException("Invalid native network deadline");
        Long previous=ACTIVE.get();
        long now=System.nanoTime();
        long requested=now+limit.toNanos();
        if(requested<now)requested=Long.MAX_VALUE;
        long effective=previous==null?requested:Math.min(previous,requested);
        ACTIVE.set(effective);
        return new Scope(previous);
    }

    public static boolean active() {return ACTIVE.get()!=null;}

    public static Long currentDeadline() {return ACTIVE.get();}

    /**
     * Explicitly bind an active deadline to a pooled or worker thread.
     * Prevents pooled worker threads from retaining expired ThreadLocal deadlines across rounds.
     */
    public static Scope bind(Long explicitDeadline) {
        Long previous=ACTIVE.get();
        if(explicitDeadline==null)ACTIVE.remove();
        else ACTIVE.set(explicitDeadline);
        return new Scope(previous);
    }

    /** Preserve an existing continuous-session budget rather than resetting it. */
    public static Scope openIfAbsent(Duration limit) {
        return ACTIVE.get()==null?open(limit):new Scope(ACTIVE.get());
    }

    /** Remaining time for an operation with a narrower local cap. */
    public static Duration cap(Duration requested) throws IOException {
        if(requested==null || requested.isZero() || requested.isNegative())
            throw new IllegalArgumentException("Invalid requested network timeout");
        if(Thread.currentThread().isInterrupted())
            throw new IOException("Native network worker interrupted");
        Long until=ACTIVE.get();
        if(until==null)return requested;
        long left=until-System.nanoTime();
        if(left<=0)throw new SocketTimeoutException(DEADLINE_EXCEEDED);
        return Duration.ofNanos(Math.min(left,requested.toNanos()));
    }

    public static void check() throws IOException {
        cap(Duration.ofDays(7));
    }

    public static boolean exhausted() {
        Long until=ACTIVE.get();
        return until!=null && until-System.nanoTime()<=0;
    }

    public static final class Scope implements AutoCloseable {
        private final Long previous;
        private boolean closed;
        private Scope(Long previous){this.previous=previous;}
        @Override public void close() {
            if(closed)return;
            closed=true;
            if(previous==null)ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
}
