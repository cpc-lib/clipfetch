package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerIncrementalFrameReaderTest {
    @Test
    void readFrameAppliesEachChangedSocketTimeout() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var socket=new RecordingSocket();
        var input=new TimeoutInputStream();

        assertNull(reader.readFrame(input,socket,1234));
        assertEquals(1234,socket.timeout);
        assertNull(reader.readFrame(input,socket,7));
        assertEquals(7,socket.timeout);
        assertEquals(2,socket.setCalls);
    }

    @Test
    void partialFrameSurvivesTimeoutAndCompletesOnNextRead() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var socket=new RecordingSocket();
        var input=new SplitThenTimeoutInputStream(validUnchokeFrame(),2);

        assertNull(reader.readFrame(input,socket,50));
        assertTrue(reader.hasPartialFrame());

        var frame=reader.readFrame(input,socket,200);
        assertEquals(200,socket.timeout);
        assertNotNull(frame);
        assertEquals(1,frame.id());
        assertFalse(reader.hasPartialFrame());
    }

    @Test
    void partialPayloadSurvivesTimeoutAndCompletesOnNextRead() throws Exception {
        var reader=new PeerIncrementalFrameReader();
        var socket=new RecordingSocket();
        var input=new SplitThenTimeoutInputStream(validPayloadFrame(),5);

        assertNull(reader.readFrame(input,socket,50));
        assertTrue(reader.hasPartialFrame());

        var frame=reader.readFrame(input,socket,200);
        assertEquals(200,socket.timeout);
        assertNotNull(frame);
        assertEquals(4,frame.id());
        assertEquals(4,frame.payload().length);
        assertFalse(reader.hasPartialFrame());
    }

    private static byte[] validUnchokeFrame() {
        return ByteBuffer.allocate(5).putInt(1).put((byte)1).array();
    }

    private static byte[] validPayloadFrame() {
        return ByteBuffer.allocate(9).putInt(5).put((byte)4).putInt(7).array();
    }

    private static final class RecordingSocket extends Socket {
        private int timeout;
        private int setCalls;

        @Override
        public boolean isClosed() { return false; }

        @Override
        public void setSoTimeout(int timeout) { this.timeout=timeout;setCalls++; }
    }

    private static final class TimeoutInputStream extends InputStream {
        @Override
        public int available() { return 0; }

        @Override
        public int read() throws IOException { throw new SocketTimeoutException("test timeout"); }

        @Override
        public int read(byte[] bytes,int offset,int length)
                throws IOException { throw new SocketTimeoutException("test timeout"); }
    }

    private static final class SplitThenTimeoutInputStream extends InputStream {
        private final byte[] bytes;
        private final int firstChunkSize;
        private int offset;
        private boolean firstChunk=true;
        private boolean timeoutThrown;

        private SplitThenTimeoutInputStream(byte[] bytes,int firstChunkSize) {
            this.bytes=bytes;
            this.firstChunkSize=firstChunkSize;
        }

        @Override
        public int available() { return 0; }

        @Override
        public int read() throws IOException {
            byte[] one=new byte[1];
            int read=read(one,0,1);
            return read<0?-1:one[0]&0xff;
        }

        @Override
        public int read(byte[] target,int targetOffset,int length)
                throws IOException {
            if(firstChunk) {
                int amount=Math.min(length,firstChunkSize-offset);
                System.arraycopy(bytes,offset,target,targetOffset,amount);
                offset+=amount;
                if(offset>=firstChunkSize)firstChunk=false;
                return amount;
            }
            if(!timeoutThrown) {
                timeoutThrown=true;
                throw new SocketTimeoutException("test timeout");
            }
            if(offset>=bytes.length)return -1;
            int amount=Math.min(length,bytes.length-offset);
            System.arraycopy(bytes,offset,target,targetOffset,amount);
            offset+=amount;
            return amount;
        }
    }
}
