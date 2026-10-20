package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * BEP3 CANCEL for unacknowledged Block requests after slow-Peer preemption.
 * This is best effort: the owner closes the unhealthy socket regardless;
 * no cancelled or partial Block can enter durable Piece progress.
 */
final class PeerBlockCancellation {
    private PeerBlockCancellation() {}
    static int cancelOutstanding(OutputStream output,PeerWireCodec wire,
                                 int pieceIndex,PeerBlockRequestWindow queue) throws IOException {
        if(output==null || wire==null || queue==null || pieceIndex<0)
            throw new IllegalArgumentException("Invalid Block cancellation");
        var pending=queue.pendingBlocks();
        if(pending.size()>PeerBlockRequestWindow.MAX_ALLOWED)
            throw new IllegalStateException("Exceeded bounded outstanding Block window");
        for(var block:pending) {
            var request=ByteBuffer.allocate(12)
                    .putInt(pieceIndex).putInt(block.begin()).putInt(block.length());
            output.write(wire.encodeMessage(
                    new PeerWireCodec.Message(8,request.array())).array());
        }
        if(!pending.isEmpty())output.flush();
        return pending.size();
    }
}
