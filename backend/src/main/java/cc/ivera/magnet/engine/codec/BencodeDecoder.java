package cc.ivera.magnet.engine.codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/** Bounded BEP 3 decoder, preserving the exact original raw 'info' dictionary bytes. */
public final class BencodeDecoder {
    public static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_ELEMENTS = 200_000;

    public sealed interface Value permits IntegerValue, BytesValue, ListValue, DictionaryValue {}
    public record IntegerValue(long value) implements Value {}
    public record BytesValue(byte[] bytes) implements Value {
        public BytesValue { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record ListValue(List<Value> values) implements Value {
        public ListValue { values = List.copyOf(values); }
    }
    public record DictionaryValue(Map<ByteString, Value> values) implements Value {
        public DictionaryValue { values = Map.copyOf(values); }
        public Value get(String name) { return values.get(ByteString.ascii(name)); }
        public Value required(String name) {
            Value item = get(name);
            if (item == null) throw new IllegalArgumentException("Missing bencode key: " + name);
            return item;
        }
    }
    public record TorrentDocument(DictionaryValue root, byte[] infoBytes) {
        public TorrentDocument { infoBytes = infoBytes.clone(); }
        @Override public byte[] infoBytes() { return infoBytes.clone(); }
    }

    public Value decode(byte[] input) {
        Parser parser = new Parser(input);
        Value value = parser.readValue(0);
        parser.requireEof();
        return value;
    }

    public TorrentDocument decodeTorrent(byte[] input) {
        Parser p = new Parser(input);
        if (p.take() != 'd') throw new IllegalArgumentException("Torrent root must be a dictionary");
        LinkedHashMap<ByteString, Value> entries = new LinkedHashMap<>();
        ByteString previous = null;
        byte[] info = null;
        while (p.peek() != 'e') {
            p.recordEntry();
            ByteString key = new ByteString(p.readBytes());
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new IllegalArgumentException("Dictionary keys must be unique and sorted");
            }
            previous = key;
            int start = p.position;
            Value val = p.readValue(1);
            if (key.equals(ByteString.ascii("info"))) {
                if (!(val instanceof DictionaryValue)) throw new IllegalArgumentException("Torrent info must be a dictionary");
                info = Arrays.copyOfRange(p.input, start, p.position);
            }
            entries.put(key, val);
        }
        p.take();
        p.requireEof();
        if (info == null) throw new IllegalArgumentException("Torrent missing info dictionary");
        return new TorrentDocument(new DictionaryValue(entries), info);
    }

    private static final class Parser {
        private final byte[] input;
        private int position;
        private int elements;
        Parser(byte[] input) {
            if (input == null || input.length == 0 || input.length > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("Bencode input size out of bounds");
            }
            this.input = input;
        }
        int peek() {
            if (position >= input.length) throw new IllegalArgumentException("Unexpected bencode end");
            return input[position] & 0xff;
        }
        int take() { int c = peek(); position++; return c; }
        void requireEof() { if (position != input.length) throw new IllegalArgumentException("Trailing bencode bytes"); }
        void recordEntry() { if (++elements > MAX_ELEMENTS) throw new IllegalArgumentException("Too many bencode elements"); }

        Value readValue(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("Bencode nesting too deep");
            recordEntry();
            int c = peek();
            if (c == 'i') {
                take();
                int start = position;
                while (peek() != 'e') {
                    int digit = take();
                    if (digit != '-' && (digit < '0' || digit > '9')) throw new IllegalArgumentException("Invalid integer");
                    if (position - start > 21) throw new IllegalArgumentException("Oversized bencode integer");
                }
                String s = new String(input, start, position - start, StandardCharsets.US_ASCII);
                take();
                if (s.isEmpty() || s.equals("-0") || (s.length() > 1 && s.charAt(0) == '0')
                        || (s.startsWith("-0"))) throw new IllegalArgumentException("Invalid bencode integer format");
                try { return new IntegerValue(Long.parseLong(s)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Bencode integer overflow", e); }
            }
            if (c >= '0' && c <= '9') return new BytesValue(readBytes());
            if (c == 'l') {
                take();
                List<Value> values = new ArrayList<>();
                while (peek() != 'e') values.add(readValue(depth + 1));
                take();
                return new ListValue(values);
            }
            if (c == 'd') {
                take();
                Map<ByteString, Value> values = new LinkedHashMap<>();
                ByteString previous = null;
                while (peek() != 'e') {
                    recordEntry();
                    ByteString key = new ByteString(readBytes());
                    if (previous != null && previous.compareTo(key) >= 0)
                        throw new IllegalArgumentException("Dictionary keys must be unique and sorted");
                    previous = key;
                    values.put(key, readValue(depth + 1));
                }
                take();
                return new DictionaryValue(values);
            }
            throw new IllegalArgumentException("Unexpected bencode marker: " + c);
        }

        byte[] readBytes() {
            int start = position;
            while (peek() != ':') {
                int c = take();
                if (c < '0' || c > '9' || position - start > 9) throw new IllegalArgumentException("Invalid bencode length");
            }
            String lenString = new String(input, start, position - start, StandardCharsets.US_ASCII);
            take();
            if (lenString.isEmpty() || (lenString.length() > 1 && lenString.charAt(0) == '0'))
                throw new IllegalArgumentException("Noncanonical bencode length");
            long len;
            try { len = Long.parseLong(lenString); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bencode length overflow", e); }
            if (len < 0 || len > input.length - position || len > MAX_METADATA_BYTES)
                throw new IllegalArgumentException("Bencode byte string too large or truncated");
            byte[] value = Arrays.copyOfRange(input, position, position + (int) len);
            position += (int) len;
            return value;
        }
    }
}
