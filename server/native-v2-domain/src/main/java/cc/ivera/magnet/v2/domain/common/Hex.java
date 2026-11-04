package cc.ivera.magnet.v2.domain.common;

import java.util.HexFormat;

public final class Hex {

    private static final HexFormat FORMAT = HexFormat.of();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        return bytes == null ? null : FORMAT.formatHex(bytes);
    }

    public static byte[] decode(String hex) {
        return hex == null ? null : FORMAT.parseHex(hex);
    }
}
