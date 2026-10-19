package cc.ivera.magnet.engine.peer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Framing and validation for BEP 3 TCP peer messages. Does not perform socket I/O. */
public final class PeerWireCodec {
    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    public static final int HANDSHAKE_LENGTH = 49 + PROTOCOL.length;
    public static final int DEFAULT_MAX_FRAME = 2 * 1024 * 1024;

    public record Handshake(byte[] reserved, byte[] infoHash, byte[] peerId) {
        public Handshake {
            if (reserved.length != 8 || infoHash.length != 20 || peerId.length != 20)
                throw new IllegalArgumentException("Invalid peer handshake fields");
            reserved = reserved.clone();
            infoHash = infoHash.clone();
            peerId = peerId.clone();
        }
        @Override public byte[] reserved() { return reserved.clone(); }
        @Override public byte[] infoHash() { return infoHash.clone(); }
        @Override public byte[] peerId() { return peerId.clone(); }
    }

    public record Message(int id, byte[] payload) {
        public Message { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        public boolean keepAlive() { return id == -1; }
    }

    public ByteBuffer encodeHandshake(Handshake data) {
        ByteBuffer out = ByteBuffer.allocate(HANDSHAKE_LENGTH);
        out.put((byte) PROTOCOL.length).put(PROTOCOL)
           .put(data.reserved()).put(data.infoHash()).put(data.peerId());
        return out.flip();
    }

    /** Returns empty on a partial handshake without consuming any bytes. */
    public Optional<Handshake> decodeHandshake(ByteBuffer input) {
        int start = input.position();
        if (input.remaining() < HANDSHAKE_LENGTH) return Optional.empty();
        if (Byte.toUnsignedInt(input.get()) != PROTOCOL.length) {
            input.position(start); throw new IllegalArgumentException("Invalid handshake pstrlen");
        }
        for (byte b : PROTOCOL) {
            if (input.get() != b) {
                input.position(start); throw new IllegalArgumentException("Invalid peer protocol name");
            }
        }
        byte[] reserved = new byte[8], infoHash = new byte[20], peerId = new byte[20];
        input.get(reserved).get(infoHash).get(peerId);
        return Optional.of(new Handshake(reserved, infoHash, peerId));
    }

    public ByteBuffer encodeMessage(Message message) {
        validate(message);
        int length = message.keepAlive() ? 0 : 1 + message.payload.length;
        ByteBuffer out = ByteBuffer.allocate(4 + length);
        out.putInt(length);
        if (length > 0) out.put((byte) message.id).put(message.payload);
        return out.flip();
    }

    /** Reads exactly one length-prefixed frame; partial data leaves buffer position unchanged. */
    public Optional<Message> decodeMessage(ByteBuffer input) {
        return decodeMessage(input, DEFAULT_MAX_FRAME);
    }

    public Optional<Message> decodeMessage(ByteBuffer input, int maxFrame) {
        if (maxFrame < 1 || maxFrame > DEFAULT_MAX_FRAME) throw new IllegalArgumentException("Invalid max frame");
        int start = input.position();
        if (input.remaining() < 4) return Optional.empty();
        int length = input.getInt();
        if (length < 0 || length > maxFrame) {
            input.position(start); throw new IllegalArgumentException("Invalid peer message length");
        }
        if (input.remaining() < length) {
            input.position(start); return Optional.empty();
        }
        if (length == 0) return Optional.of(new Message(-1, new byte[0]));
        int id = Byte.toUnsignedInt(input.get());
        byte[] payload = new byte[length - 1];
        input.get(payload);
        Message parsed = new Message(id, payload);
        validate(parsed);
        return Optional.of(parsed);
    }

    private static void validate(Message message) {
        int length = message.payload.length;
        switch (message.id) {
            case -1, 0, 1, 2, 3 -> {
                if (length != 0) throw new IllegalArgumentException("Unexpected fixed peer message payload");
            }
            case 4 -> {
                if (length != 4 || ByteBuffer.wrap(message.payload).getInt() < 0)
                    throw new IllegalArgumentException("Invalid have message");
            }
            case 5 -> { /* bitfield can be empty only for an empty torrent; validate against metainfo later */ }
            case 6, 8 -> {
                if (length != 12) throw new IllegalArgumentException("Invalid request/cancel length");
                ByteBuffer b = ByteBuffer.wrap(message.payload);
                int piece = b.getInt(), offset = b.getInt(), blockSize = b.getInt();
                if (piece < 0 || offset < 0 || blockSize < 1 || blockSize > 128 * 1024)
                    throw new IllegalArgumentException("Invalid block range");
            }
            case 7 -> {
                if (length < 8) throw new IllegalArgumentException("Invalid piece payload");
                ByteBuffer b = ByteBuffer.wrap(message.payload);
                if (b.getInt() < 0 || b.getInt() < 0) throw new IllegalArgumentException("Invalid piece coordinates");
            }
            case 9 -> {
                if (length != 2) throw new IllegalArgumentException("Invalid port message");
            }
            case 20 -> {
                if (length < 1) throw new IllegalArgumentException("Missing extension message id");
            }
            default -> throw new IllegalArgumentException("Unsupported peer message id: " + message.id);
        }
    }
}
