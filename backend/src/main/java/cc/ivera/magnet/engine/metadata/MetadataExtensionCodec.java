package cc.ivera.magnet.engine.metadata;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** BEP10 extension handshake and BEP9 ut_metadata message headers. */
public final class MetadataExtensionCodec {
    private final BencodeDecoder decoder = new BencodeDecoder();
    private final BencodeEncoder encoder = new BencodeEncoder();

    public byte[] extensionHandshake() {
        return encoder.encode(new DictionaryValue(Map.of(
            ByteString.ascii("m"), new DictionaryValue(Map.of(
                ByteString.ascii("ut_metadata"), new IntegerValue(1))))));
    }
    public record ExtensionInfo(int utMetadataId, int metadataSize) {}
    public ExtensionInfo parseExtensionHandshake(byte[] payload) {
        if (!(decoder.decode(payload) instanceof DictionaryValue root)
                || !(root.required("m") instanceof DictionaryValue methods)
                || !(methods.get("ut_metadata") instanceof IntegerValue ut)) {
            throw new IllegalArgumentException("Malformed BEP10 handshake");
        }
        long id=ut.value();
        if (id < 1 || id > 255) throw new IllegalArgumentException("Invalid ut_metadata id");
        int size=-1;
        if (root.get("metadata_size") instanceof IntegerValue length) {
            if (length.value() < 1 || length.value() > BencodeDecoder.MAX_METADATA_BYTES)
                throw new IllegalArgumentException("Invalid metadata size");
            size=(int)length.value();
        }
        return new ExtensionInfo((int)id,size);
    }
    public byte[] request(int piece) {
        if (piece < 0 || piece >= 512) throw new IllegalArgumentException("Invalid metadata piece");
        return encoder.encode(new DictionaryValue(Map.of(
                ByteString.ascii("msg_type"),new IntegerValue(0),
                ByteString.ascii("piece"),new IntegerValue(piece))));
    }
    public record MetadataMessage(int type, int piece, int totalSize, byte[] data) {
        public MetadataMessage { data=data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    /** BEP9 response is a bencoded dictionary followed by binary piece bytes. */
    public MetadataMessage parseMessage(byte[] payload) {
        if (payload == null || payload.length < 2 || payload.length > 16384 + 512)
            throw new IllegalArgumentException("Metadata frame length");
        int headerEnd=findDictionaryEnd(payload);
        byte[] header=Arrays.copyOf(payload,headerEnd);
        if (!(decoder.decode(header) instanceof DictionaryValue root))
            throw new IllegalArgumentException("Invalid metadata message");
        int type=number(root,"msg_type"),piece=number(root,"piece");
        if (type < 0 || type > 2 || piece < 0 || piece >= 512) throw new IllegalArgumentException("Invalid metadata header");
        int total=root.get("total_size")==null ? -1 : number(root,"total_size");
        if (total != -1 && (total < 1 || total > BencodeDecoder.MAX_METADATA_BYTES))
            throw new IllegalArgumentException("Invalid metadata total size");
        byte[] bytes=Arrays.copyOfRange(payload,headerEnd,payload.length);
        if (type != 1 && bytes.length != 0) throw new IllegalArgumentException("Unexpected metadata data");
        if (type == 1 && bytes.length == 0) throw new IllegalArgumentException("Empty metadata data");
        return new MetadataMessage(type,piece,total,bytes);
    }
    private static int number(DictionaryValue dict,String key) {
        if (!(dict.required(key) instanceof IntegerValue n) || n.value() < Integer.MIN_VALUE || n.value() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid metadata integer");
        return (int)n.value();
    }
    private static int findDictionaryEnd(byte[] payload) {
        if (payload[0] != 'd') throw new IllegalArgumentException("Not a metadata dictionary");
        int index=1,depth=1;
        while (index < payload.length && depth > 0) {
            int ch=payload[index] & 255;
            if (ch == 'd' || ch == 'l') { depth++; index++; }
            else if (ch == 'e') { depth--; index++; }
            else if (ch == 'i') {
                index++;
                while (index < payload.length && payload[index] != 'e') index++;
                index++;
            } else if (ch >= '0' && ch <= '9') {
                long length=0;
                while (index < payload.length && payload[index] != ':') {
                    int c=payload[index++] & 255;
                    if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid bencode header");
                    length=length*10+c-'0';
                    if (length > payload.length) throw new IllegalArgumentException("Invalid bencode header length");
                }
                if (index >= payload.length) throw new IllegalArgumentException("Missing bencode colon");
                index++;
                if (length > payload.length-index) throw new IllegalArgumentException("Truncated bencode bytes");
                index+=(int)length;
            } else throw new IllegalArgumentException("Invalid bencode token");
            if (depth > 48) throw new IllegalArgumentException("Metadata header too deep");
        }
        if (depth != 0) throw new IllegalArgumentException("Incomplete metadata header");
        return index;
    }
}
