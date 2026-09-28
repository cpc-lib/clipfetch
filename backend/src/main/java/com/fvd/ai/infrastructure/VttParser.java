package com.fvd.ai.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fvd.ai.domain.SubtitleData;

/**
 * WebVTT 字幕解析器：VTT 文本 → 结构化分段（去除滚动字幕的重复行）
 */
public final class VttParser {

    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})");

    private VttParser() {
    }

    public static List<SubtitleData.Segment> parse(String vtt) {
        List<SubtitleData.Segment> segments = new ArrayList<>();
        String[] blocks = vtt.replace("\r\n", "\n").replace("\r", "\n").split("\n\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            Matcher m = null;
            int timeLineIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                m = TIMESTAMP.matcher(lines[i]);
                if (m.find()) {
                    timeLineIdx = i;
                    break;
                }
            }
            if (timeLineIdx < 0) {
                continue;
            }
            double start = toSeconds(m.group(1), m.group(2), m.group(3), m.group(4));
            double end = toSeconds(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder text = new StringBuilder();
            for (int i = timeLineIdx + 1; i < lines.length; i++) {
                String line = cleanLine(lines[i]);
                if (!line.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(line);
                }
            }
            String t = text.toString().trim();
            if (t.isEmpty()) {
                continue;
            }
            // 滚动字幕去重：与上一段文本完全相同则只延长时间
            if (!segments.isEmpty()) {
                SubtitleData.Segment prev = segments.get(segments.size() - 1);
                if (prev.text().equals(t)) {
                    segments.set(segments.size() - 1,
                            new SubtitleData.Segment(prev.start(), end, prev.startDisplay(), display(end), t));
                    continue;
                }
            }
            segments.add(new SubtitleData.Segment(start, end, display(start), display(end), t));
        }
        return segments;
    }

    /** 也兼容 SRT 文本 */
    private static String cleanLine(String line) {
        return line
                .replaceAll("<[^>]+>", "")          // VTT 标签
                .replaceAll("\\{[^}]+}", "")        // ASS 样式
                .trim();
    }

    private static double toSeconds(String h, String m, String s, String ms) {
        return Integer.parseInt(h) * 3600 + Integer.parseInt(m) * 60
                + Integer.parseInt(s) + Integer.parseInt(ms) / 1000.0;
    }

    public static String display(double seconds) {
        long total = (long) seconds;
        long h = total / 3600, mm = total % 3600 / 60, ss = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, mm, ss) : String.format("%02d:%02d", mm, ss);
    }
}
