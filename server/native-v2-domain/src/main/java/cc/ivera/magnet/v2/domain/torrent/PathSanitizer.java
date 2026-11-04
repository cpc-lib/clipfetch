package cc.ivera.magnet.v2.domain.torrent;

import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 相对路径安全校验：拒绝目录穿越、绝对路径、Windows 驱动器名、
 * NUL/控制字符、保留设备名、结尾点/空格、超长路径、同名大小写冲突。
 */
public final class PathSanitizer {

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³");

    public static final int MAX_COMPONENT_LENGTH = 255;
    public static final int MAX_PATH_LENGTH = 1024;

    private PathSanitizer() {
    }

    public static String sanitizeComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw invalid("路径分量不能为空");
        }
        if (raw.equals(".") || raw.equals("..")) {
            throw invalid("路径分量不允许为 " + raw);
        }
        if (raw.startsWith(" ")) {
            throw invalid("路径分量不能以空格开头");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '<' || c == '>' || c == '"'
                    || c == '|' || c == '?' || c == '*' || c < 0x20 || c == 0x7f) {
                throw invalid("路径分量含非法字符: " + describe(c));
            }
        }
        if (raw.length() > MAX_COMPONENT_LENGTH) {
            throw invalid("路径分量超长");
        }
        if (raw.endsWith(".") || raw.endsWith(" ")) {
            throw invalid("路径分量不能以点或空格结尾");
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String baseName = dot < 0 ? upper : upper.substring(0, dot);
        if (WINDOWS_RESERVED.contains(baseName)) {
            throw invalid("路径分量是 Windows 保留名: " + raw);
        }
        return raw;
    }

    /** 校验并拼接为以 '/' 分隔的规范相对路径；同时拒绝大小写冲突 */
    public static String joinRelativePath(List<String> components) {
        if (components == null || components.isEmpty()) {
            throw invalid("路径分量列表不能为空");
        }
        StringBuilder sb = new StringBuilder();
        for (String component : components) {
            String clean = sanitizeComponent(component);
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(clean);
        }
        if (sb.length() > MAX_PATH_LENGTH) {
            throw invalid("相对路径超长");
        }
        return sb.toString();
    }

    private static String describe(char c) {
        return c < 0x20 || c == 0x7f ? "U+" + String.format("%04X", (int) c) : String.valueOf(c);
    }

    private static V2DomainException invalid(String reason) {
        return new V2DomainException(ErrorCodes.INVALID_PATH, reason);
    }
}
