package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only per-task block planning for BEP3 Piece requests.
 * Selected file bytes may overlap pieces also containing unselected file ranges:
 * the whole boundary piece must be fetched and verified.
 */
public final class PieceBlockPlanner {
    public static final int BLOCK_BYTES = 16 * 1024;
    public record Block(int pieceIndex,int begin,int length) {
        public Block {
            if (pieceIndex < 0 || begin < 0 || length < 1 || length > BLOCK_BYTES)
                throw new IllegalArgumentException("Invalid block");
        }
    }

    private final TorrentInfo torrent;
    private final BitSet wanted;
    private final int pieceCount;

    public PieceBlockPlanner(TorrentInfo torrent,Set<Integer> selectedFiles) {
        if (torrent == null) throw new IllegalArgumentException("Missing torrent");
        this.torrent=torrent;
        this.pieceCount=torrent.pieceLayout().pieceCount();
        if ((long)pieceCount * 20 != torrent.pieceSha1().length)
            throw new IllegalArgumentException("Torrent has inconsistent piece hashes");
        this.wanted=torrent.pieceLayout().wantedPieces(selectedFiles);
    }
    public BitSet wantedPieces() { return (BitSet)wanted.clone(); }
    public int pieceCount() { return pieceCount; }
    public int pieceBytes(int piece) {
        if (piece < 0 || piece >= pieceCount) throw new IllegalArgumentException("Piece index out of range");
        long begin=(long)piece * torrent.pieceLength();
        return (int)Math.min(torrent.pieceLength(),torrent.totalBytes()-begin);
    }
    public List<Block> blocks(int piece) {
        if (!wanted.get(piece)) throw new IllegalArgumentException("Piece not selected");
        int bytes=pieceBytes(piece);
        List<Block> result=new ArrayList<>((bytes+BLOCK_BYTES-1)/BLOCK_BYTES);
        for (int begin=0;begin<bytes;begin+=BLOCK_BYTES)
            result.add(new Block(piece,begin,Math.min(BLOCK_BYTES,bytes-begin)));
        return List.copyOf(result);
    }
}
