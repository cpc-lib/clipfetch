package cc.ivera.ed2k.infrastructure;

import cc.ivera.shared.web.BusinessException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class FilePathPolicy {

    private FilePathPolicy() {
    }

    public static Path resolveVerifiedCompletedFile(Path incomingRoot, String relativePath, long expectedFileSize) {
        if (incomingRoot == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "未配置电驴已完成文件目录");
        }
        validateRelativeFileName(relativePath);

        Path normalizedRoot = incomingRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(HttpStatus.CONFLICT, "电驴已完成目录不存在或不可访问");
        }

        final Path candidate;
        try {
            candidate = normalizedRoot.resolve(relativePath).normalize();
        } catch (InvalidPathException ex) {
            throw new BusinessException("已完成文件路径非法");
        }

        if (!candidate.startsWith(normalizedRoot)) {
            throw new BusinessException("拒绝访问越界文件路径");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(HttpStatus.CONFLICT, "已完成文件不存在或尚未落盘");
        }
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException("拒绝访问符号链接或非常规文件");
        }

        try {
            Path realRoot = normalizedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realFile = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(realRoot)) {
                throw new BusinessException("拒绝访问越界文件路径");
            }
            long actualSize = Files.size(realFile);
            if (actualSize != expectedFileSize) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "已完成文件大小校验不一致（期望 " + expectedFileSize + " 字节，实际 " + actualSize + " 字节）");
            }
            return realFile;
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "校验已完成文件失败：" + ex.getMessage());
        }
    }

    public static void validateRelativeFileName(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        if (relativePath.indexOf('\0') >= 0 || relativePath.indexOf('\r') >= 0 || relativePath.indexOf('\n') >= 0) {
            throw new BusinessException("文件名包含非法控制字符");
        }
        if (relativePath.contains("/") || relativePath.contains("\\") || relativePath.contains("..")) {
            throw new BusinessException("文件名不能包含路径分隔符或上级目录引用");
        }
        if (".".equals(relativePath) || "..".equals(relativePath)) {
            throw new BusinessException("文件名非法");
        }
        Path parsed;
        try {
            parsed = Path.of(relativePath);
        } catch (InvalidPathException ex) {
            throw new BusinessException("文件名路径格式非法");
        }
        if (parsed.isAbsolute() || parsed.getNameCount() != 1) {
            throw new BusinessException("仅允许单层文件名，不允许嵌套路径");
        }
    }

    public static String buildContentDispositionAttachment(String fileName) {
        String safeName = (fileName == null || fileName.isBlank()) ? "download.bin" : fileName.trim();
        StringBuilder asciiFallback = new StringBuilder(safeName.length());
        for (int i = 0; i < safeName.length(); i++) {
            char ch = safeName.charAt(i);
            if (ch >= 0x20 && ch <= 0x7E && ch != '"' && ch != ';' && ch != '\\' && ch != '/') {
                asciiFallback.append(ch);
            } else {
                asciiFallback.append('_');
            }
        }
        String fallback = asciiFallback.isEmpty() ? "download.bin" : asciiFallback.toString();
        String encoded = rfc5987Encode(safeName);
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String rfc5987Encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '!' || c == '#' || c == '$' || c == '&' || c == '+'
                    || c == '-' || c == '.' || c == '^' || c == '_' || c == '`'
                    || c == '|' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
