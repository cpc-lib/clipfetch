package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Pure BEP3 request/piece/cancel wire adapter for PeerBlockMailbox.
 * No network socket ownership; caller must verify a task-scoped handshake,
 * own the session, enforce deadlines and commit SHA-1-verified Pieces only.
 */
final class PeerBlockMailboxFrames {
    private PeerBlockMailboxFrames() {}
    static PeerWireCodec.Message request(PeerBlockMailbox.Request lease) {
        return keyedFrame(6,lease.key());
    }
    static PeerWireCodec.Message cancel(PeerBlockMailbox.Request loser) {
        return keyedFrame(8,loser.key());
    }
    private static PeerWireCodec.Message keyedFrame(int id,PeerBlockMailbox.Key block) {
        var body=ByteBuffer.allocate(12).putInt(block.piece())
                .putInt(block.begin()).putInt(block.length()).array();
        return new PeerWireCodec.Message(id,body);
    }
    static PeerBlockMailbox.Receipt piece(PeerBlockMailbox mailbox,
                                         InetSocketAddress sender,
                                         PeerWireCodec.Message incoming) throws IOException {
        if(mailbox==null || sender==null || incoming==null || incoming.id()!=7)
            throw new IOException("Unexpected Peer Block response");
        var bytes=incoming.payload();
        if(bytes.length<9 || bytes.length>PieceBlockPlanner.BLOCK_BYTES+8)
            throw new IOException("Invalid Peer Block length");
        var buffer=ByteBuffer.wrap(bytes);
        int index=buffer.getInt(),begin=buffer.getInt();
        byte[] body=new byte[buffer.remaining()];
        buffer.get(body);
        PeerBlockMailbox.Key key;
        try {key=new PeerBlockMailbox.Key(index,begin,body.length);}
        catch(IllegalArgumentException exception) {
            throw new IOException("Invalid requested Block identity",exception);
        }
        return mailbox.deliver(sender,key,body);
    }
}
