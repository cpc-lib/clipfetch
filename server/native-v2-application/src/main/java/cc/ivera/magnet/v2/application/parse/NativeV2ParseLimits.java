package cc.ivera.magnet.v2.application.parse;

/** 解析输入的配置上限；数据库与 Worker 的可信校验都以此为准。 */
public record NativeV2ParseLimits(int maxTrackers, int maxFiles, long maxTorrentBytes, long maxTotalBytes) {

    public NativeV2ParseLimits {
        if (maxTrackers < 0 || maxFiles <= 0 || maxTorrentBytes <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("解析上限必须为正数");
        }
    }
}
