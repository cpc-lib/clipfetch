package cc.ivera.magnet.engine.metadata;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MetadataProtocolTest {
    @Test void extendedHandshakeAndMetadataHeaders() {
        var codec=new MetadataExtensionCodec();
        assertEquals(1,codec.parseExtensionHandshake(codec.extensionHandshake()).utMetadataId());
        assertEquals(0,codec.parseMessage(codec.request(0)).type());
        byte[] header="d8:msg_typei1e5:piecei0e10:total_sizei4ee".getBytes(StandardCharsets.US_ASCII);
        byte[] frame=Arrays.copyOf(header,header.length+4);
        System.arraycopy(new byte[]{1,2,3,4},0,frame,header.length,4);
        var message=codec.parseMessage(frame);
        assertEquals(1,message.type());
        assertEquals(4,message.totalSize());
        assertArrayEquals(new byte[]{1,2,3,4},message.data());
    }
    @Test void assembledInfoMustMatchExpectedSha1() throws Exception {
        byte[] info="d4:name4:teste".getBytes(StandardCharsets.US_ASCII);
        byte[] hash=MessageDigest.getInstance("SHA-1").digest(info);
        var assembler=new MetadataAssembler(hash,info.length);
        assembler.add(0,info);
        assertTrue(assembler.isComplete());
        assertArrayEquals(info,assembler.verifiedInfoDictionary());
        var bad=new MetadataAssembler(new byte[20],info.length);
        bad.add(0,info);
        assertThrows(IllegalArgumentException.class,bad::verifiedInfoDictionary);
    }
    @Test void outOfOrderBlocksAndDuplicates() throws Exception {
        byte[] data=new byte[MetadataAssembler.BLOCK_SIZE+5];
        Arrays.fill(data,(byte)7);
        var hash=MessageDigest.getInstance("SHA-1").digest(data);
        var assembler=new MetadataAssembler(hash,data.length);
        assembler.add(1,Arrays.copyOfRange(data,MetadataAssembler.BLOCK_SIZE,data.length));
        assertFalse(assembler.isComplete());
        assembler.add(0,Arrays.copyOfRange(data,0,MetadataAssembler.BLOCK_SIZE));
        assertTrue(assembler.isComplete());
        assertThrows(IllegalArgumentException.class,assembler::verifiedInfoDictionary);
        assertThrows(IllegalArgumentException.class,() -> assembler.add(1,new byte[5]));
    }
}
