package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Stateless BEP3 v1 piece SHA-1 verification. NEVER use this alone to credit persisted progress. */
public final class PieceHashVerifier {
    private PieceHashVerifier() {}

    public static boolean verify(TorrentInfo torrent,int pieceIndex,byte[] data) {
        if (torrent == null || data == null) throw new IllegalArgumentException("Missing piece");
        int pieceCount=torrent.pieceLayout().pieceCount();
        if (pieceIndex < 0 || pieceIndex >= pieceCount)
            throw new IllegalArgumentException("Piece index out of range");
        long from=(long)pieceIndex*torrent.pieceLength();
        int expectedLength=(int)Math.min(torrent.pieceLength(),torrent.totalBytes()-from);
        if (data.length != expectedLength) return false;
        byte[] hashes=torrent.pieceSha1();
        if ((long)hashes.length != (long)pieceCount*20)
            throw new IllegalArgumentException("Torrent piece hash count mismatch");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-1").digest(data);
            return MessageDigest.isEqual(digest,Arrays.copyOfRange(hashes,pieceIndex*20,pieceIndex*20+20));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
