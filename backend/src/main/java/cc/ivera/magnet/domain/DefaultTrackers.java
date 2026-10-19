package cc.ivera.magnet.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 常用高可用公共与官方 Tracker 列表，以及磁力链接 Tracker 自动增强工具。
 * 针对纯 Info Hash 或缺少 tr 参数的磁力链接，自动补充高质量 Tracker，
 * 解决国内/无公网 IP 环境下仅靠 DHT 导致寻轨缓慢、Peer 数量少、下载速度慢的问题。
 */
public final class DefaultTrackers {

    private DefaultTrackers() {}

    /**
     * 精选高可用公共 Tracker 列表（包含 Ubuntu 官方 Tracker 与全球优质公共 Tracker）
     */
    public static final List<String> TRACKERS = List.of(
            // Ubuntu 官方高可用 Tracker（官方 ISO 必备，单节点通常有上千做种者）
            "http://torrent.ubuntu.com:6969/announce",
            "https://torrent.ubuntu.com/announce",
            // 全球连通率最高、更新最及时的公共 Tracker
            "http://tracker.opentrackr.org:1337/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.nyaa.vc:6969/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://tracker-udp.gbitt.info:80/announce",
            "udp://tracker.qu.ax:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.farted.net:6969/announce",
            "udp://tracker.peerfect.org:6969/announce"
    );

    /**
     * 为磁力链接自动注入默认 Tracker 参数。
     * 若磁力链接中已包含对应 Tracker，则不重复追加。
     *
     * @param magnetUri 原始磁力链接
     * @return 增强 Tracker 后的磁力链接
     */
    public static String enrichMagnet(String magnetUri) {
        if (magnetUri == null || !magnetUri.startsWith("magnet:?")) {
            return magnetUri;
        }
        StringBuilder sb = new StringBuilder(magnetUri.trim());
        for (String tr : TRACKERS) {
            String encoded = URLEncoder.encode(tr, StandardCharsets.UTF_8);
            if (!magnetUri.contains(encoded) && !magnetUri.contains(tr)) {
                sb.append("&tr=").append(encoded);
            }
        }
        return sb.toString();
    }
}
