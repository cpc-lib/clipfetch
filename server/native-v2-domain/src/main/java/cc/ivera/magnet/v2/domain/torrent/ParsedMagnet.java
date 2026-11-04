package cc.ivera.magnet.v2.domain.torrent;

import cc.ivera.magnet.v2.domain.resource.VersionType;

import java.util.List;

/** Magnet URI 解析结果。dn 与 xl 仅是提示，不能作为可信元数据或磁盘分配依据。 */
public record ParsedMagnet(byte[] btih,
                           byte[] btmh,
                           List<String> trackers,
                           String displayNameHint,
                           Long exactLengthHint) {

    public ParsedMagnet {
        btih = copy(btih);
        btmh = copy(btmh);
        trackers = List.copyOf(trackers);
    }

    @Override
    public byte[] btih() {
        return copy(btih);
    }

    @Override
    public byte[] btmh() {
        return copy(btmh);
    }

    public VersionType versionType() {
        if (btih != null && btmh != null) {
            return VersionType.HYBRID;
        }
        return btih != null ? VersionType.V1 : VersionType.V2;
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }
}
