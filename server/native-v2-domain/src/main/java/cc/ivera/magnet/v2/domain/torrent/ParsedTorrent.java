package cc.ivera.magnet.v2.domain.torrent;

import cc.ivera.magnet.v2.domain.resource.VersionType;

import java.util.List;

/** .torrent 元数据解析结果 */
public record ParsedTorrent(byte[] infoHashV1,
                            byte[] infoHashV2,
                            VersionType versionType,
                            String name,
                            long pieceLength,
                            long totalBytes,
                            boolean privateFlag,
                            List<String> trackers,
                            List<ParsedTorrentFile> files) {

    public ParsedTorrent {
        infoHashV1 = copy(infoHashV1);
        infoHashV2 = copy(infoHashV2);
        trackers = List.copyOf(trackers);
        files = List.copyOf(files);
    }

    @Override
    public byte[] infoHashV1() {
        return copy(infoHashV1);
    }

    @Override
    public byte[] infoHashV2() {
        return copy(infoHashV2);
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }
}
