package cc.ivera.magnet.engine.metadata;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;

/** Per-session, bounded BEP9 raw info dictionary assembler, authenticated by InfoHash SHA-1. */
public final class MetadataAssembler {
    public static final int BLOCK_SIZE=16*1024;
    private final byte[] expectedHash;
    private final byte[] data;
    private final BitSet received;
    private final int count;
    private int remaining;
    public MetadataAssembler(byte[] expectedHash,int totalSize) {
        if (expectedHash == null || expectedHash.length != 20 || totalSize < 1
                || totalSize > BencodeDecoder.MAX_METADATA_BYTES) throw new IllegalArgumentException("Invalid metadata");
        this.expectedHash=expectedHash.clone();
        this.data=new byte[totalSize];
        this.count=(totalSize+BLOCK_SIZE-1)/BLOCK_SIZE;
        this.received=new BitSet(count);
        this.remaining=count;
    }
    public int pieceCount() { return count; }
    public boolean isComplete() { return remaining == 0; }
    public void add(int piece,byte[] content) {
        if (piece < 0 || piece >= count || content == null) throw new IllegalArgumentException("Invalid piece");
        int offset=piece*BLOCK_SIZE;
        int required=Math.min(BLOCK_SIZE,data.length-offset);
        if (content.length != required) throw new IllegalArgumentException("Wrong metadata piece size");
        if (received.get(piece)) {
            if (!Arrays.equals(Arrays.copyOfRange(data,offset,offset+required),content))
                throw new IllegalArgumentException("Conflicting metadata piece");
            return;
        }
        System.arraycopy(content,0,data,offset,required);
        received.set(piece);
        remaining--;
    }
    public byte[] verifiedInfoDictionary() {
        if (!isComplete()) throw new IllegalStateException("Metadata not complete");
        try {
            byte[] sha1=MessageDigest.getInstance("SHA-1").digest(data);
            if (!MessageDigest.isEqual(sha1,expectedHash))
                throw new IllegalArgumentException("Metadata InfoHash mismatch");
            if (!(new BencodeDecoder().decode(data) instanceof BencodeDecoder.DictionaryValue))
                throw new IllegalArgumentException("Metadata info is not a dictionary");
            return data.clone();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
