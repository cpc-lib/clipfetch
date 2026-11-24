package cc.ivera.parser.infrastructure.playback;

import cc.ivera.parser.application.PlaybackTicket;
import cc.ivera.parser.application.PlaybackTicketStore;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 改写：把所有叶子 URI 登记成子 token，替换为同源 /api/play/hls/... 地址。
 * 只替换 URI 文本，不重排标签、不修改时长/序号。
 */
@Component
public class HlsPlaylistRewriter {

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    public String rewrite(String playlist, URI baseUri, PlaybackTicket ticket,
                          PlaybackTicketStore store) {
        String[] lines = playlist.split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.startsWith("#")) {
                    line = rewriteUriAttributes(line, baseUri, ticket, store);
                } else {
                    boolean pinned = isPlaylistUri(trimmed);
                    line = childUrl(baseUri.resolve(trimmed), ticket, store, pinned,
                            pinned ? "application/vnd.apple.mpegurl" : null);
                }
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private String rewriteUriAttributes(String line, URI baseUri, PlaybackTicket ticket,
                                        PlaybackTicketStore store) {
        Matcher m = URI_ATTR.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group(1);
            if (value == null || value.isBlank()) {
                continue;
            }
            URI resolved = baseUri.resolve(value);
            boolean pinned = !"#EXT-X-PART".equals(tagOf(line));
            String replacement = "URI=\"" + childUrl(resolved, ticket, store, pinned,
                    pinned ? "application/vnd.apple.mpegurl" : null) + "\"";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String tagOf(String line) {
        int colon = line.indexOf(':');
        return colon > 0 ? line.substring(0, colon) : line;
    }

    private String childUrl(URI uri, PlaybackTicket ticket, PlaybackTicketStore store,
                            boolean pinned, String contentTypeHint) {
        String token = store.registerChild(ticket, uri, contentTypeHint, pinned);
        return "/api/play/hls/" + ticket.getToken() + "/" + token;
    }

    private boolean isPlaylistUri(String uri) {
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return path.toLowerCase().endsWith(".m3u8");
    }
}
