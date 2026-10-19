package cc.ivera.magnet.engine.codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bencode dictionary keys and binary values must not be coerced to text. */
public final class ByteString implements Comparable<ByteString> {
    private final byte[] bytes;
    public ByteString(byte[] bytes) { this.bytes = bytes.clone(); }
    public static ByteString ascii(String value) { return new ByteString(value.getBytes(StandardCharsets.US_ASCII)); }
    public byte[] bytes() { return bytes.clone(); }
    @Override public int compareTo(ByteString other) {
        int n = Math.min(bytes.length, other.bytes.length);
        for (int i = 0; i < n; i++) {
            int a = bytes[i] & 0xff, b = other.bytes[i] & 0xff;
            if (a != b) return Integer.compare(a, b);
        }
        return Integer.compare(bytes.length, other.bytes.length);
    }
    @Override public boolean equals(Object obj) { return obj instanceof ByteString that && Arrays.equals(bytes, that.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return new String(bytes, StandardCharsets.UTF_8); }
}
