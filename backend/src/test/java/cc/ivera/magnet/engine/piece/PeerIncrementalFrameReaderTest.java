package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PeerIncrementalFrameReaderTest {
    private static final PeerWireCodec CODEC=new PeerWireCodec();
    @Test void retainsPartialLengthHeaderAndBodyAcrossPolls() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var stream=new Feed();
        byte[] piece=ByteBuffer.allocate(12).putInt(3).putInt(0)
                .put(new byte[]{1,2,3,4}).array();
        byte[] frame=CODEC.encodeMessage(new PeerWireCodec.Message(7,piece)).array();
        stream.push(frame,0,2);
        assertNull(reader.poll(stream));
        assertTrue(reader.hasPartialFrame());
        stream.push(frame,2,3);
        assertNull(reader.poll(stream));
        stream.push(frame,5,4);
        assertNull(reader.poll(stream));
        stream.push(frame,9,frame.length-9);
        var decoded=reader.poll(stream);
        assertNotNull(decoded);
        assertEquals(7,decoded.id());
        assertArrayEquals(piece,decoded.payload());
        assertFalse(reader.hasPartialFrame());
        assertNull(reader.poll(stream));
    }
    @Test void adjacentFramesAndKeepAliveDoNotLoseBoundaries() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var stream=new Feed();
        byte[] first=CODEC.encodeMessage(new PeerWireCodec.Message(-1,new byte[0])).array();
        byte[] second=CODEC.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array();
        stream.push(first,0,first.length);
        stream.push(second,0,second.length);
        assertEquals(-1,reader.poll(stream).id());
        assertEquals(1,reader.poll(stream).id());
        assertNull(reader.poll(stream));
    }
    @Test void oversizedFrameRejectedBeforeAllocatingBody() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var stream=new Feed();
        var tooLarge=ByteBuffer.allocate(4).putInt(65537).array();
        stream.push(tooLarge,0,4);
        assertThrows(IOException.class,()->reader.poll(stream));
    }
    @Test void readFrameOverSocketAssemblesFragmentedFramesAcrossTimeouts() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        try(var server=new ServerSocket(0);
            var client=new Socket("127.0.0.1",server.getLocalPort());
            var accepted=server.accept()) {
            client.setTcpNoDelay(true);
            accepted.setTcpNoDelay(true);

            byte[] piece=ByteBuffer.allocate(12).putInt(5).putInt(16384)
                    .put(new byte[]{9,8,7,6}).array();
            byte[] frame=CODEC.encodeMessage(new PeerWireCodec.Message(7,piece)).array();

            // 1. Send first 2 bytes of header only
            accepted.getOutputStream().write(frame,0,2);
            accepted.getOutputStream().flush();

            // Calling readFrame with 50ms timeout should read partial header and return null upon timeout
            var first=reader.readFrame(client.getInputStream(),client,50);
            assertNull(first);
            assertTrue(reader.hasPartialFrame());

            // 2. Send remaining bytes
            accepted.getOutputStream().write(frame,2,frame.length-2);
            accepted.getOutputStream().flush();

            var decoded=reader.readFrame(client.getInputStream(),client,200);
            assertNotNull(decoded);
            assertEquals(7,decoded.id());
            assertArrayEquals(piece,decoded.payload());
            assertFalse(reader.hasPartialFrame());
        }
    }
    private static final class Feed extends InputStream {
        private byte[] data=new byte[0];
        private int index;
        void push(byte[] source,int from,int length){
            byte[] left=Arrays.copyOfRange(data,index,data.length);
            data=Arrays.copyOf(left,left.length+length);
            System.arraycopy(source,from,data,left.length,length);
            index=0;
        }
        @Override public int available(){return data.length-index;}
        @Override public int read() {return index>=data.length?-1:data[index++]&0xff;}
        @Override public int read(byte[] b,int off,int len) {
            if(available()==0)return -1;
            int count=Math.min(len,available());
            System.arraycopy(data,index,b,off,count);
            index+=count;
            return count;
        }
    }
}
