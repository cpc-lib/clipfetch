package cc.ivera.magnet.engine.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Canonical BEP3 encoder, useful for protocol fixtures and future KRPC. */
public final class BencodeEncoder {
    public byte[] encode(BencodeDecoder.Value value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }
    private void write(BencodeDecoder.Value val, ByteArrayOutputStream out) {
        if (val instanceof BencodeDecoder.IntegerValue num) {
            out.write('i');
            ascii(out, Long.toString(num.value()));
            out.write('e');
        } else if (val instanceof BencodeDecoder.BytesValue b) {
            bytes(out, b.bytes());
        } else if (val instanceof BencodeDecoder.ListValue list) {
            out.write('l');
            for (var child : list.values()) write(child, out);
            out.write('e');
        } else if (val instanceof BencodeDecoder.DictionaryValue dict) {
            out.write('d');
            for (Map.Entry<ByteString, BencodeDecoder.Value> e : new TreeMap<>(dict.values()).entrySet()) {
                bytes(out, e.getKey().bytes());
                write(e.getValue(), out);
            }
            out.write('e');
        } else {
            throw new IllegalArgumentException("Unknown bencode value");
        }
    }
    private static void bytes(ByteArrayOutputStream out, byte[] value) {
        ascii(out, Integer.toString(value.length));
        out.write(':');
        out.writeBytes(value);
    }
    private static void ascii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
