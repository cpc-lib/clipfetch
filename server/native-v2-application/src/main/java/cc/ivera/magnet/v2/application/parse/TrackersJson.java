package cc.ivera.magnet.v2.application.parse;

import java.util.ArrayList;
import java.util.List;

/** 受控 Tracker 列表与 JSON 字符串数组互转；不接受任意 JSON 结构。 */
final class TrackersJson {

    private TrackersJson() {
    }

    static String write(List<String> trackers) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < trackers.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"');
            for (char c : trackers.get(i).toCharArray()) {
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            json.append(String.format("\\u%04x", (int) c));
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            json.append('"');
        }
        return json.append(']').toString();
    }

    static List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> trackers = new ArrayList<>();
        int index = 0;
        skipWhitespace(json, index);
        if (json.charAt(index) != '[') {
            throw new IllegalArgumentException("Tracker JSON 必须是数组");
        }
        index++;
        while (true) {
            index = skipWhitespace(json, index);
            if (json.charAt(index) == ']') {
                index++;
                break;
            }
            if (json.charAt(index) != '"') {
                throw new IllegalArgumentException("Tracker JSON 元素必须是字符串");
            }
            StringBuilder value = new StringBuilder();
            index++;
            while (json.charAt(index) != '"') {
                char c = json.charAt(index);
                if (c != '\\') {
                    value.append(c);
                    index++;
                    continue;
                }
                index++;
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Tracker JSON 转义非法");
                }
            }
            index++;
            trackers.add(value.toString());
            index = skipWhitespace(json, index);
            if (json.charAt(index) == ',') {
                index++;
            }
        }
        return List.copyOf(trackers);
    }

    private static int skipWhitespace(String json, int index) {
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        return index;
    }
}
