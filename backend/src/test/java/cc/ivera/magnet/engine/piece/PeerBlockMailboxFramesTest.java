package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerBlockMailboxFramesTest {
    private final PeerWireCodec codec=new PeerWireCodec();
    private static InetSocketAddress peer(int last) {
        return InetSocketAddress.createUnresolved("8.8.4."+last,6881);
    }
    private static PeerWireCodec.Message roundTrip(PeerWireCodec wire,PeerWireCodec.Message frame) {
        return wire.decodeMessage(wire.encodeMessage(frame)).orElseThrow();
    }
    @Test void endgameRequestsAndCancelFramesMatchActualBep3WireProtocol() throws Exception {
        var slow=peer(1);var fast=peer(2);var outsider=peer(3);
        var key=new PeerBlockMailbox.Key(7,16384,16384);
        var queue=new PeerBlockMailbox(List.of(key));
        var availability=new PeerPieceAvailability(8,Map.of());
        var original=queue.claim(slow,availability,1,1).get(0);
        var request=roundTrip(codec,PeerBlockMailboxFrames.request(original));
        assertEquals(6,request.id());
        var fields=ByteBuffer.wrap(request.payload());
        assertEquals(7,fields.getInt());assertEquals(16384,fields.getInt());
        assertEquals(16384,fields.getInt());
        var backup=queue.claim(fast,availability,2_300_000_000L,1).get(0);
        assertTrue(backup.backup());
        var raw=ByteBuffer.allocate(8+16384).putInt(7).putInt(16384)
                .put(new byte[16384]).array();
        var piece=roundTrip(codec,new PeerWireCodec.Message(7,raw));
        var outcome=PeerBlockMailboxFrames.piece(queue,fast,piece);
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,outcome.delivery());
        assertEquals(1,outcome.winner().cancel().size());
        var canceled=roundTrip(codec,PeerBlockMailboxFrames.cancel(
                outcome.winner().cancel().get(0)));
        assertEquals(8,canceled.id());
        assertArrayEquals(request.payload(),canceled.payload());
        assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                PeerBlockMailboxFrames.piece(queue,slow,piece).delivery());
        assertThrows(IOException.class,
                ()->PeerBlockMailboxFrames.piece(queue,outsider,piece));
        assertEquals(0,queue.remaining());
        assertEquals(0,queue.peerOutstanding(slow));
        assertEquals(0,queue.peerOutstanding(fast));
    }

    @Test void validatesRequestedPieceIdentityLengthAndUnknownSources() throws Exception {
        var a=peer(1);
        var key=new PeerBlockMailbox.Key(0,0,4);
        var queue=new PeerBlockMailbox(List.of(key));
        var available=new PeerPieceAvailability(1,Map.of());
        queue.claim(a,available,100,1);
        assertThrows(IOException.class,
                ()->PeerBlockMailboxFrames.piece(queue,a,new PeerWireCodec.Message(6,new byte[12])));
        assertThrows(IOException.class,
                ()->PeerBlockMailboxFrames.piece(queue,a,new PeerWireCodec.Message(7,new byte[8])));
        var payload=ByteBuffer.allocate(12).putInt(0).putInt(0).putInt(42).array();
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                PeerBlockMailboxFrames.piece(queue,a,new PeerWireCodec.Message(7,payload))
                        .delivery());
    }
}
