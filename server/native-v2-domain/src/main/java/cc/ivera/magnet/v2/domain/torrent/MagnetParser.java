package cc.ivera.magnet.v2.domain.torrent;

import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.Hex;
import cc.ivera.magnet.v2.domain.common.V2DomainException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Magnet URI 解析：独立识别并验证 xt / tr / dn / xl；btih 支持 40 位十六进制与 Base32；
 * btmh 使用 multihash（sha2-256: 0x12 0x20 + 32 字节）并规范化为 32 字节摘要。
 */
public final class MagnetParser {

    private static final String PREFIX = "magnet:?";
    private static final int MAX_URI_LENGTH = 8192;
    private static final int MAX_TRACKERS = 64;
    private static final int MAX_DISPLAY_NAME = 512;
    private static final Set<String> TRACKER_SCHEMES = Set.of("http", "https", "udp");
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public ParsedMagnet parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw invalid("URI 为空");
        }
        String trimmed = uri.trim();
        if (trimmed.length() > MAX_URI_LENGTH) {
            throw invalid("URI 超长");
        }
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw invalid("缺少 magnet:? 前缀");
        }
        String query = trimmed.substring(PREFIX.length());

        byte[] btih = null;
        byte[] btmh = null;
        Set<String> trackers = new LinkedHashSet<>();
        String displayName = null;
        Long exactLength = null;

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw invalid("参数缺少 '='");
            }
            String key = decodeComponent(pair.substring(0, eq));
            String rawValue = pair.substring(eq + 1);
            if (key.equals("tr")) {
                String tracker = tryDecodeComponent(rawValue);
                if (tracker != null && isAllowedTracker(tracker)
                        && (trackers.contains(tracker) || trackers.size() < MAX_TRACKERS)) {
                    trackers.add(tracker);
                }
                continue;
            }
            String value = decodeComponent(rawValue);
            switch (key) {
                case "xt" -> {
                    if (value.regionMatches(true, 0, "urn:btih:", 0, 9)) {
                        byte[] parsedBtih = parseBtih(value.substring(9));
                        if (btih != null && !Arrays.equals(btih, parsedBtih)) {
                            throw invalid("存在冲突的 btih");
                        }
                        btih = parsedBtih;
                    } else if (value.regionMatches(true, 0, "urn:btmh:", 0, 9)) {
                        byte[] parsedBtmh = parseBtmh(value.substring(9));
                        if (btmh != null && !Arrays.equals(btmh, parsedBtmh)) {
                            throw invalid("存在冲突的 btmh");
                        }
                        btmh = parsedBtmh;
                    }
                }
                case "dn" -> {
                    if (value.length() <= MAX_DISPLAY_NAME && !value.isBlank()) {
                        displayName = value;
                    }
                }
                case "xl" -> {
                    try {
                        long parsed = Long.parseLong(value);
                        if (parsed >= 0) {
                            exactLength = parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // xl 仅为提示，非法即忽略
                    }
                }
                default -> {
                    // 未知参数忽略
                }
            }
        }

        if (btih == null && btmh == null) {
            throw invalid("缺少有效 xt（btih 或 btmh）");
        }
        return new ParsedMagnet(btih, btmh, List.copyOf(trackers), displayName, exactLength);
    }

    private static byte[] parseBtih(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9a-f]{40}")) {
            return Hex.decode(lower);
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.matches("[A-Z2-7]{32}")) {
            return base32Decode(upper);
        }
        throw invalid("btih 必须是 40 位十六进制或 32 位 Base32");
    }

    private static byte[] parseBtmh(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.matches("[0-9a-f]{68}")) {
            throw invalid("btmh 必须是 68 位十六进制 multihash");
        }
        byte[] multihash = Hex.decode(lower);
        if ((multihash[0] & 0xff) != 0x12 || (multihash[1] & 0xff) != 0x20) {
            throw invalid("btmh multihash 必须是 sha2-256（0x12 0x20 前缀）");
        }
        byte[] digest = new byte[32];
        System.arraycopy(multihash, 2, digest, 0, digest.length);
        return digest;
    }

    static boolean isAllowedTracker(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !TRACKER_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return false;
            }
            return hasValidPort(uri, scheme);
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean hasValidPort(URI uri, String scheme) {
        String authority = uri.getRawAuthority();
        boolean explicitPort;
        int portStart;
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) {
                return false;
            }
            explicitPort = closingBracket + 1 < authority.length();
            if (explicitPort && authority.charAt(closingBracket + 1) != ':') {
                return false;
            }
            portStart = closingBracket + 2;
        } else {
            int colon = authority.lastIndexOf(':');
            explicitPort = colon >= 0;
            portStart = colon + 1;
        }
        if (!explicitPort) {
            return !scheme.equalsIgnoreCase("udp");
        }
        String portText = authority.substring(portStart);
        if (portText.isEmpty() || !portText.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            int port = Integer.parseInt(portText);
            return port >= 1 && port <= 65_535 && uri.getPort() == port;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static byte[] base32Decode(String text) {
        int bits = 0;
        int buffer = 0;
        byte[] out = new byte[20];
        int outPos = 0;
        for (int i = 0; i < text.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(text.charAt(i));
            if (value < 0) {
                throw invalid("Base32 含非法字符");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (outPos >= out.length) {
                    throw invalid("Base32 解码长度越界");
                }
                out[outPos++] = (byte) (buffer >>> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        if (outPos != 20) {
            throw invalid("btih Base32 解码后必须是 20 字节");
        }
        return out;
    }

    private static String decodeComponent(String raw) {
        String value = tryDecodeComponent(raw);
        if (value == null) {
            throw invalid("参数百分号编码非法");
        }
        return value;
    }

    private static String tryDecodeComponent(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static V2DomainException invalid(String reason) {
        return new V2DomainException(ErrorCodes.INVALID_MAGNET, reason);
    }
}
