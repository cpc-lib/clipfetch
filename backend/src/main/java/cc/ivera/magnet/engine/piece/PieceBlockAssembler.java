package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;

import java.util.Arrays;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, single-piece buffer for exact BEP3 16 KiB blocks.
 * Network completion never implies durable storage or downloaded progress.
 */
public final class PieceBlockAssembler {
    public static final int MAX_BUFFERED_PIECE_BYTES = 8 * 1024 * 1024;

    private final int pieceIndex;
    private final byte[] buffer;
    private final BitSet received;
    private final int blockCount;
    private int completedBlocks;

    public PieceBlockAssembler(PieceBlockPlanner planner, int pieceIndex) {
        if (planner == null) throw new IllegalArgumentException("Missing block planner");
        int size = planner.pieceBytes(pieceIndex);
        if (!planner.wantedPieces().get(pieceIndex)) throw new IllegalArgumentException("Piece not wanted");
        if (size < 1 || size > MAX_BUFFERED_PIECE_BYTES)
            throw new IllegalArgumentException("Piece exceeds buffering limit");
        this.pieceIndex = pieceIndex;
        this.buffer = new byte[size];
        this.blockCount = (size + PieceBlockPlanner.BLOCK_BYTES - 1) / PieceBlockPlanner.BLOCK_BYTES;
        this.received = new BitSet(blockCount);
    }

    public int pieceIndex() { return pieceIndex; }
    public int blockCount() { return blockCount; }
    public int receivedBlocks() { return completedBlocks; }
    public boolean isComplete() { return completedBlocks == blockCount; }
    /** Retain successful untrusted blocks only within the same in-flight Piece.
     * Nothing here is durable or reported as completed until final SHA-1 passes.
     */
    public List<PieceBlockPlanner.Block> missingBlocks(PieceBlockPlanner planner) {
        if(planner==null)throw new IllegalArgumentException("Missing block planner");
        var missing=new ArrayList<PieceBlockPlanner.Block>();
        for(var block:planner.blocks(pieceIndex)) {
            if(!received.get(block.begin()/PieceBlockPlanner.BLOCK_BYTES))
                missing.add(block);
        }
        return List.copyOf(missing);
    }
    /** A whole-Piece hash mismatch invalidates all retained blocks. */
    public void reset() {
        Arrays.fill(buffer,(byte)0);
        received.clear();
        completedBlocks=0;
    }

    /** Accept any arrival order; require correct offset, size, and identical duplicate bytes. */
    public void accept(int piece, int begin, byte[] data) {
        if (piece != pieceIndex || data == null || begin < 0
                || begin % PieceBlockPlanner.BLOCK_BYTES != 0 || begin >= buffer.length)
            throw new IllegalArgumentException("Unexpected Piece block position");
        int expected = Math.min(PieceBlockPlanner.BLOCK_BYTES, buffer.length - begin);
        if (data.length != expected) throw new IllegalArgumentException("Unexpected Piece block length");
        int slot = begin / PieceBlockPlanner.BLOCK_BYTES;
        if (received.get(slot)) {
            if (!Arrays.equals(buffer, begin, begin + expected, data, 0, data.length))
                throw new IllegalArgumentException("Conflicting repeated Piece block");
            return;
        }
        System.arraycopy(data, 0, buffer, begin, data.length);
        received.set(slot);
        completedBlocks++;
    }

    public byte[] verifiedPiece(TorrentInfo torrent) {
        if (!isComplete()) throw new IllegalStateException("Piece has missing blocks");
        if (!PieceHashVerifier.verify(torrent, pieceIndex, buffer))
            throw new IllegalArgumentException("Piece SHA-1 verification failed");
        return buffer.clone();
    }
}
