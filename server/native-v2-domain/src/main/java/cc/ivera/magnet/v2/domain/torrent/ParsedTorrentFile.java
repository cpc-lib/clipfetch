package cc.ivera.magnet.v2.domain.torrent;

/** @param v2PiecesRoot V2 文件 Merkle 根（32 字节）；V1 文件或零长 V2 文件为 null */
public record ParsedTorrentFile(int index,
                                String relativePath,
                                long sizeBytes,
                                byte[] v2PiecesRoot) {

    public ParsedTorrentFile {
        v2PiecesRoot = copy(v2PiecesRoot);
    }

    @Override
    public byte[] v2PiecesRoot() {
        return copy(v2PiecesRoot);
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }
}
