package cc.ivera.ed2k.domain;

import cc.ivera.shared.web.BusinessException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Ed2kLinkParser {

    private static final String PREFIX = "ed2k://|";
    private static final String SUFFIX = "|/";
    private static final Pattern SIZE_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern MD4_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final int MAX_FILENAME_LENGTH = 255;

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private Ed2kLinkParser() {
    }

    public static Ed2kParsedLink parse(String rawLink, int maxLinkLength, long maxFileBytes) {
        if (rawLink == null || rawLink.isBlank()) {
            throw new BusinessException("请输入有效的 eD2k 下载链接");
        }
        if (rawLink.indexOf('\r') >= 0 || rawLink.indexOf('\n') >= 0 || rawLink.indexOf('\0') >= 0) {
            throw new BusinessException("一次仅支持提交单条 eD2k 链接，且不能包含换行或控制字符");
        }
        String trimmed = rawLink.trim();
        if (trimmed.length() > maxLinkLength) {
            throw new BusinessException("eD2k 链接长度超出上限（最大 " + maxLinkLength + " 字符）");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(PREFIX)) {
            throw new BusinessException("无效的 eD2k 链接：必须以 ed2k://| 开头");
        }
        if (lower.indexOf("ed2k://", PREFIX.length()) >= 0) {
            throw new BusinessException("一次仅支持提交单条 eD2k 链接");
        }
        if (!trimmed.endsWith(SUFFIX)) {
            throw new BusinessException("无效的 eD2k 链接：必须以 |/ 结尾且不支持附加尾部参数");
        }

        String body = trimmed.substring(PREFIX.length(), trimmed.length() - SUFFIX.length());
        String[] parts = body.split("\\|", -1);
        if (parts.length == 0 || !"file".equalsIgnoreCase(parts[0])) {
            throw new BusinessException("仅支持 ed2k://|file| 文件下载链接");
        }
        if (parts.length != 4) {
            throw new BusinessException("eD2k 文件链接格式非法，标准格式为 ed2k://|file|文件名|大小|MD4|/");
        }

        String rawName = parts[1];
        String rawSize = parts[2];
        String rawMd4 = parts[3];

        String decodedName = decodeAndValidateFileName(rawName);
        long fileSize = parseAndValidateSize(rawSize, maxFileBytes);
        String md4 = validateAndNormalizeMd4(rawMd4);

        Ed2kIdentity identity = new Ed2kIdentity(md4, fileSize);
        String canonicalLink = PREFIX + "file|" + percentEncodeComponent(decodedName) + "|" + fileSize + "|" + md4 + SUFFIX;
        return new Ed2kParsedLink(identity, decodedName, canonicalLink);
    }

    private static String decodeAndValidateFileName(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            throw new BusinessException("eD2k 链接中的文件名不能为空");
        }
        String decoded = percentDecodeStrict(rawName);
        if (decoded.isBlank()) {
            throw new BusinessException("eD2k 链接中的文件名不能为空");
        }
        if (decoded.length() > MAX_FILENAME_LENGTH) {
            throw new BusinessException("eD2k 文件名长度不能超过 " + MAX_FILENAME_LENGTH + " 个字符");
        }
        if (".".equals(decoded) || "..".equals(decoded)) {
            throw new BusinessException("eD2k 文件名非法");
        }
        if (decoded.endsWith(" ") || decoded.endsWith(".")) {
            throw new BusinessException("eD2k 文件名不能以空格或句点结尾");
        }
        for (int i = 0; i < decoded.length(); i++) {
            char ch = decoded.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                throw new BusinessException("eD2k 文件名不能包含控制字符");
            }
            if (ch == '/' || ch == '\\') {
                throw new BusinessException("eD2k 文件名不能包含路径分隔符");
            }
            if (ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                throw new BusinessException("eD2k 文件名包含系统不支持的特殊字符");
            }
        }
        int dotIndex = decoded.indexOf('.');
        String stem = (dotIndex >= 0 ? decoded.substring(0, dotIndex) : decoded).trim().toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(stem)) {
            throw new BusinessException("eD2k 文件名不能使用系统保留设备名");
        }
        return decoded;
    }

    private static long parseAndValidateSize(String rawSize, long maxFileBytes) {
        if (rawSize == null || !SIZE_PATTERN.matcher(rawSize).matches()) {
            throw new BusinessException("eD2k 文件大小必须为正整数");
        }
        final long size;
        try {
            size = Long.parseLong(rawSize);
        } catch (NumberFormatException ex) {
            throw new BusinessException("eD2k 文件大小超出数值范围");
        }
        if (size <= 0) {
            throw new BusinessException("eD2k 文件大小必须大于 0");
        }
        if (size > maxFileBytes) {
            throw new BusinessException("eD2k 文件大小超出系统上限（最大 " + maxFileBytes + " 字节）");
        }
        return size;
    }

    private static String validateAndNormalizeMd4(String rawMd4) {
        if (rawMd4 == null || !MD4_PATTERN.matcher(rawMd4).matches()) {
            throw new BusinessException("eD2k 链接中的 MD4 哈希必须为 32 位十六进制字符串");
        }
        return rawMd4.toLowerCase(Locale.ROOT);
    }

    private static String percentDecodeStrict(String input) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%') {
                if (i + 2 >= input.length()) {
                    throw new BusinessException("eD2k 链接文件名 URL 编码不完整");
                }
                int hi = Character.digit(input.charAt(i + 1), 16);
                int lo = Character.digit(input.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new BusinessException("eD2k 链接文件名包含无效的 URL 编码");
                }
                baos.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] charBytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                baos.writeBytes(charBytes);
            }
        }
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(baos.toByteArray()));
            return cb.toString();
        } catch (CharacterCodingException ex) {
            throw new BusinessException("eD2k 链接文件名不是有效的 UTF-8 编码");
        }
    }

    private static String percentEncodeComponent(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            if (isUnreserved(unsigned)) {
                sb.append((char) unsigned);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }
}
