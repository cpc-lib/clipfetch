package cc.ivera.magnet.v2.domain.resource;

public final class TorrentFileInfo {

    private final int fileIndex;
    private final String relativePath;
    private final long sizeBytes;
    private final String v2PiecesRootHex;

    /** MyBatis 用：匹配 wrapper */
    public TorrentFileInfo(Integer fileIndex, String relativePath, Long sizeBytes, String v2PiecesRootHex) {
        this.fileIndex = fileIndex == null ? 0 : fileIndex;
        this.relativePath = relativePath;
        this.sizeBytes = sizeBytes == null ? 0L : sizeBytes;
        this.v2PiecesRootHex = v2PiecesRootHex;
    }

    /** 应用层用：primitive */
    public TorrentFileInfo(int fileIndex, String relativePath, long sizeBytes, String v2PiecesRootHex) {
        this.fileIndex = fileIndex;
        this.relativePath = relativePath;
        this.sizeBytes = sizeBytes;
        this.v2PiecesRootHex = v2PiecesRootHex;
    }

    public int getFileIndex() {
        return fileIndex;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getV2PiecesRootHex() {
        return v2PiecesRootHex;
    }
}
