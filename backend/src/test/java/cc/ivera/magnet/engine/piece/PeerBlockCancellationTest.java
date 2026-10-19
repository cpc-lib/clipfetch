package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerBlockCancellationTest {
    @Test void cancelsOnlyOutstandingNotAlreadyDeliveredBlocks() throws Exception {
        var blocks=new ArrayList<PieceBlockPlanner.Block>();
        for(int i=0;i<16;i++)
            blocks.add(new PieceBlockPlanner.Block(7,
                    i*PieceBlockPlanner.BLOCK_BYTES,PieceBlockPlanner.BLOCK_BYTES));
        var queue=new PeerBlockRequestWindow(blocks);
        assertEquals(16,queue.fill().size());
        queue.acknowledge(7,7,0,PieceBlockPlanner.BLOCK_BYTES);
        queue.acknowledge(7,7,3*PieceBlockPlanner.BLOCK_BYTES,
                PieceBlockPlanner.BLOCK_BYTES);
        var output=new ByteArrayOutputStream();
        int cancelled=PeerBlockCancellation.cancelOutstanding(
                output,new PeerWireCodec(),7,queue);
        assertEquals(14,cancelled);
        var encoded=ByteBuffer.wrap(output.toByteArray());
        Set<Integer> offsets=new HashSet<>();
        var codec=new PeerWireCodec();
        for(int i=0;i<cancelled;i++) {
            assertTrue(encoded.remaining()>=4);
            int size=encoded.getInt();
            assertEquals(13,size);
            byte[] raw=new byte[size+4];
            ByteBuffer.wrap(raw).putInt(size);
            encoded.get(raw,4,size);
            var cancel=codec.decodeMessage(ByteBuffer.wrap(raw)).orElseThrow();
            assertEquals(8,cancel.id());
            var request=ByteBuffer.wrap(cancel.payload());
            assertEquals(7,request.getInt());
            offsets.add(request.getInt());
            assertEquals(PieceBlockPlanner.BLOCK_BYTES,request.getInt());
        }
        assertEquals(0,encoded.remaining());
        assertEquals(14,offsets.size());
        assertFalse(offsets.contains(0));
        assertFalse(offsets.contains(3*PieceBlockPlanner.BLOCK_BYTES));
    }

    @Test void doesNotCancelCompletedBlocks() throws Exception {
        var blocks=List.of(new PieceBlockPlanner.Block(0,0,16384));
        var queue=new PeerBlockRequestWindow(blocks);
        queue.fill();
        queue.acknowledge(0,0,0,16384);
        var result=new ByteArrayOutputStream();
        assertEquals(0,PeerBlockCancellation.cancelOutstanding(
                result,new PeerWireCodec(),0,queue));
        assertEquals(0,result.size());
    }
}
