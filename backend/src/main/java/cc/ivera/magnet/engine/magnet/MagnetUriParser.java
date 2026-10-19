package cc.ivera.magnet.engine.magnet;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict parser for BitTorrent v1 magnet URIs; v2-only requests are rejected explicitly. */
public final class MagnetUriParser {
    private static final int MAX_URI_LENGTH = 16_384;
    private static final int MAX_PARAMETERS = 128;
    private static final int MAX_TRACKERS = 32;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public MagnetInfo parse(String value) {
        if (value == null || value.length() > MAX_URI_LENGTH || !value.regionMatches(true, 0, "magnet:?", 0, 8)) {
            throw new IllegalArgumentException("Invalid Magnet URI");
        }
        String query = value.substring(8);
        if (query.isEmpty()) throw new IllegalArgumentException("Magnet URI has no query");
        String[] parts = query.split("&", -1);
        if (parts.length > MAX_PARAMETERS) throw new IllegalArgumentException("Too many Magnet parameters");
        String hash = null;
        boolean v2 = false;
        Set<String> trackers = new LinkedHashSet<>();
        String displayName = null;
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals < 1 || equals == part.length() - 1) continue;
            String key = part.substring(0, equals).toLowerCase(Locale.ROOT);
            String val;
            try {
                val = URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid Magnet percent encoding", e);
            }
            if (val.length() > 4096 || val.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid Magnet parameter");
            if (key.equals("xt")) {
                if (val.regionMatches(true, 0, "urn:btih:", 0, 9)) {
                    String newHash = decodeBtih(val.substring(9));
                    if (hash != null && !hash.equals(newHash)) throw new IllegalArgumentException("Conflicting InfoHashes");
                    hash = newHash;
                } else if (val.regionMatches(true, 0, "urn:btmh:", 0, 9)) {
                    v2 = true;
                }
            } else if (key.equals("tr")) {
                URI url;
                try { url = URI.create(val); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid Tracker URI", e); }
                String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
                if ((!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("udp"))
                        || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null) {
                    throw new IllegalArgumentException("Unsupported Tracker URI");
                }
                trackers.add(url.toString());
                if (trackers.size() > MAX_TRACKERS) throw new IllegalArgumentException("Too many trackers");
            } else if (key.equals("dn")) {
                displayName = val;
            }
        }
        if (hash == null) {
            if (v2) throw new UnsupportedOperationException("BitTorrent v2-only Magnet is not supported yet");
            throw new IllegalArgumentException("Magnet is missing a v1 InfoHash");
        }
        return new MagnetInfo(hash, List.copyOf(trackers), displayName);
    }

    private static String decodeBtih(String raw) {
        if (raw.matches("(?i)[0-9a-f]{40}")) return raw.toLowerCase(Locale.ROOT);
        if (raw.length() != 32) throw new IllegalArgumentException("Invalid btih length");
        byte[] hash = new byte[20];
        int bitBuffer = 0, bits = 0, at = 0;
        for (int i = 0; i < raw.length(); i++) {
            int val = BASE32.indexOf(Character.toUpperCase(raw.charAt(i)));
            if (val < 0) throw new IllegalArgumentException("Invalid Base32 btih");
            bitBuffer = (bitBuffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                hash[at++] = (byte) (bitBuffer >>> bits);
                bitBuffer &= (1 << bits) - 1;
            }
        }
        return java.util.HexFormat.of().formatHex(hash);
    }

    public record MagnetInfo(String infoHash, List<String> trackers, String displayName) {
        public MagnetInfo { trackers = List.copyOf(trackers); }
    }
}
