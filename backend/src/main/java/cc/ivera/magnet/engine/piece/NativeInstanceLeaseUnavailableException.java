package cc.ivera.magnet.engine.piece;

/**
 * A failed instance admission is not a Piece/Peer failure.
 * No fenced checkpoint/database operation is permitted after lease loss.
 */
public final class NativeInstanceLeaseUnavailableException extends IllegalStateException {
    public NativeInstanceLeaseUnavailableException(String reason) {
        super(reason);
    }
}
