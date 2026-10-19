package cc.ivera.magnet.engine.peer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PeerWireCodecTest {
    private final PeerWireCodec codec = new PeerWireCodec();

    @Test void handshakeRoundtripAndPartialFrame() {
        byte[] hash = new byte[20];
        Arrays.fill(hash, (byte) 7);
        var handshake = new PeerWireCodec.Handshake(new byte[8], hash, new byte[20]);
        ByteBuffer encoded = codec.encodeHandshake(handshake);
        assertEquals(PeerWireCodec.HANDSHAKE_LENGTH, encoded.remaining());
        ByteBuffer partial = ByteBuffer.wrap(Arrays.copyOf(encoded.array(), 10));
        assertTrue(codec.decodeHandshake(partial).isEmpty());
        assertEquals(0, partial.position());
        assertArrayEquals(hash, codec.decodeHandshake(encoded).orElseThrow().infoHash());
    }

    @Test void messageRoundtripAndNonDestructivePartial() {
        var message = new PeerWireCodec.Message(4, ByteBuffer.allocate(4).putInt(42).array());
        ByteBuffer bytes = codec.encodeMessage(message);
        ByteBuffer partial = ByteBuffer.wrap(Arrays.copyOf(bytes.array(), 6));
        assertTrue(codec.decodeMessage(partial).isEmpty());
        assertEquals(0, partial.position());
        assertEquals(4, codec.decodeMessage(bytes).orElseThrow().id());
        assertTrue(codec.decodeMessage(codec.encodeMessage(new PeerWireCodec.Message(-1, new byte[0])))
                .orElseThrow().keepAlive());
    }

    @Test void malformedFrameIsRejected() {
        ByteBuffer oversized = ByteBuffer.allocate(4).putInt(PeerWireCodec.DEFAULT_MAX_FRAME + 1);
        oversized.flip();
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMessage(oversized));
        ByteBuffer wrong = ByteBuffer.allocate(5).putInt(1).put((byte) 250);
        wrong.flip();
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMessage(wrong));
        assertThrows(IllegalArgumentException.class, () ->
                codec.encodeMessage(new PeerWireCodec.Message(6, new byte[1])));
    }
}
