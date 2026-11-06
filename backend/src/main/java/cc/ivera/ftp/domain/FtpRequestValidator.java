package cc.ivera.ftp.domain;

import cc.ivera.shared.web.BusinessException;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class FtpRequestValidator {

    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_REMOTE_PATH_LENGTH = 4096;
    private static final int MAX_CREDENTIAL_LENGTH = 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern DNS_NAME = Pattern.compile(
            "(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(?:\\.(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?))*");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FtpRequestValidator() {
    }

    public static ValidatedRequest validate(FtpProtocol protocol,
                                            String host,
                                            int port,
                                            String remotePath,
                                            FtpAuthMode authMode,
                                            String username,
                                            String password,
                                            String displayName,
                                            String expectedSha256) {
        if (protocol == null || authMode == null) {
            throw new BusinessException("请选择 FTP/FTPS 协议和认证方式");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException("FTP 端口必须在 1 到 65535 之间");
        }
        String normalizedHost = normalizeHost(host);
        String normalizedPath = validateRemotePath(remotePath);
        String normalizedName = validateDisplayName(
                displayName == null || displayName.isBlank()
                        ? lastPathSegment(normalizedPath)
                        : displayName);

        String normalizedUser = null;
        String normalizedPassword = null;
        if (authMode == FtpAuthMode.CREDENTIALS) {
            normalizedUser = requireCredential(username, "FTP 用户名");
            normalizedPassword = requireCredential(password, "FTP 密码");
        }

        String normalizedSha = null;
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String candidate = expectedSha256.trim();
            if (!SHA256.matcher(candidate).matches()) {
                throw new BusinessException("期望 SHA-256 必须是 64 位十六进制字符串");
            }
            normalizedSha = candidate.toLowerCase(Locale.ROOT);
        }

        return new ValidatedRequest(protocol, normalizedHost, port, normalizedPath, authMode,
                normalizedUser, normalizedPassword, normalizedName, normalizedSha);
    }

    public static ValidatedConnectionTestRequest validateConnectionTestRequest(FtpProtocol protocol,
                                                                               String host,
                                                                               int port,
                                                                               String remotePath,
                                                                               FtpAuthMode authMode,
                                                                               String username,
                                                                               String password,
                                                                               String displayName,
                                                                               String expectedSha256) {
        if (protocol == null || authMode == null) {
            throw new BusinessException("请选择 FTP/FTPS 协议和认证方式");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException("FTP 端口必须在 1 到 65535 之间");
        }
        String normalizedHost = normalizeHost(host);
        String normalizedUser = null;
        String normalizedPassword = null;
        if (authMode == FtpAuthMode.CREDENTIALS) {
            normalizedUser = requireCredential(username, "FTP 用户名");
            normalizedPassword = requireCredential(password, "FTP 密码");
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String candidate = expectedSha256.trim();
            if (!SHA256.matcher(candidate).matches()) {
                throw new BusinessException("期望 SHA-256 必须是 64 位十六进制字符串");
            }
        }
        if (displayName != null && !displayName.isBlank()) {
            validateDisplayName(displayName.trim());
        }
        if (remotePath == null || remotePath.isBlank()) {
            return new ValidatedConnectionTestRequest(
                    protocol, normalizedHost, port, null, false, authMode, normalizedUser, normalizedPassword);
        }
        String trimmedPath = remotePath.trim();
        if ("/".equals(trimmedPath) || trimmedPath.endsWith("/")) {
            String normalizedDir = normalizeDirectoryPath(trimmedPath);
            return new ValidatedConnectionTestRequest(
                    protocol, normalizedHost, port, normalizedDir, true, authMode, normalizedUser, normalizedPassword);
        }
        String normalizedFilePath = validateRemotePath(trimmedPath);
        return new ValidatedConnectionTestRequest(
                protocol, normalizedHost, port, normalizedFilePath, false, authMode, normalizedUser, normalizedPassword);
    }

    public static ValidatedDirectoryRequest validateDirectoryRequest(FtpProtocol protocol,
                                                                     String host,
                                                                     int port,
                                                                     String remoteDirectoryPath,
                                                                     FtpAuthMode authMode,
                                                                     String username,
                                                                     String password) {
        if (protocol == null || authMode == null) {
            throw new BusinessException("请选择 FTP/FTPS 协议和认证方式");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException("FTP 端口必须在 1 到 65535 之间");
        }
        String normalizedHost = normalizeHost(host);
        String normalizedDir = normalizeDirectoryPath(remoteDirectoryPath);
        String normalizedUser = null;
        String normalizedPassword = null;
        if (authMode == FtpAuthMode.CREDENTIALS) {
            normalizedUser = requireCredential(username, "FTP 用户名");
            normalizedPassword = requireCredential(password, "FTP 密码");
        }
        return new ValidatedDirectoryRequest(protocol, normalizedHost, port, normalizedDir,
                authMode, normalizedUser, normalizedPassword);
    }

    public static ValidatedCreateDirectoryRequest validateCreateDirectoryRequest(FtpProtocol protocol,
                                                                                 String host,
                                                                                 int port,
                                                                                 String parentDirectoryPath,
                                                                                 String directoryName,
                                                                                 FtpAuthMode authMode,
                                                                                 String username,
                                                                                 String password) {
        ValidatedDirectoryRequest base = validateDirectoryRequest(
                protocol, host, port, parentDirectoryPath, authMode, username, password);
        String validDirName = validateEntryName(directoryName, "文件夹名称");
        String targetPath = "/".equals(base.remoteDirectoryPath())
                ? "/" + validDirName
                : base.remoteDirectoryPath() + "/" + validDirName;
        return new ValidatedCreateDirectoryRequest(
                base.protocol(), base.host(), base.port(), base.remoteDirectoryPath(),
                validDirName, targetPath, base.authMode(), base.username(), base.password());
    }

    public static ValidatedUploadRequest validateUploadRequest(FtpProtocol protocol,
                                                               String host,
                                                               int port,
                                                               String remoteDirectoryPath,
                                                               String fileName,
                                                               FtpAuthMode authMode,
                                                               String username,
                                                               String password) {
        ValidatedDirectoryRequest base = validateDirectoryRequest(
                protocol, host, port, remoteDirectoryPath, authMode, username, password);
        String validFileName = validateEntryName(fileName, "上传文件名");
        String targetRemotePath = "/".equals(base.remoteDirectoryPath())
                ? "/" + validFileName
                : base.remoteDirectoryPath() + "/" + validFileName;
        return new ValidatedUploadRequest(
                base.protocol(), base.host(), base.port(), base.remoteDirectoryPath(),
                validFileName, targetRemotePath, base.authMode(), base.username(), base.password());
    }

    public static ValidatedDeleteEntryRequest validateDeleteEntryRequest(FtpProtocol protocol,
                                                                         String host,
                                                                         int port,
                                                                         String remotePath,
                                                                         String entryType,
                                                                         FtpAuthMode authMode,
                                                                         String username,
                                                                         String password) {
        if (protocol == null || authMode == null) {
            throw new BusinessException("请选择 FTP/FTPS 协议和认证方式");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException("FTP 端口必须在 1 到 65535 之间");
        }
        String normalizedHost = normalizeHost(host);
        if (remotePath == null || remotePath.isBlank()) {
            throw new BusinessException("请选择要删除的远端条目");
        }
        String targetPath = normalizeDirectoryPath(remotePath);
        if ("/".equals(targetPath)) {
            throw new BusinessException("不能删除 FTP 根目录");
        }
        String entryName = validateEntryName(lastPathSegment(targetPath), "远端条目名称");
        String normalizedType = entryType == null ? "" : entryType.trim().toUpperCase(Locale.ROOT);
        if (!"FILE".equals(normalizedType) && !"DIRECTORY".equals(normalizedType)) {
            throw new BusinessException("仅支持删除远端文件或文件夹");
        }
        String normalizedUser = null;
        String normalizedPassword = null;
        if (authMode == FtpAuthMode.CREDENTIALS) {
            normalizedUser = requireCredential(username, "FTP 用户名");
            normalizedPassword = requireCredential(password, "FTP 密码");
        }
        return new ValidatedDeleteEntryRequest(protocol, normalizedHost, port, targetPath, entryName,
                normalizedType, authMode, normalizedUser, normalizedPassword);
    }

    public static String normalizeDirectoryPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_REMOTE_PATH_LENGTH
                || containsControl(trimmed)
                || trimmed.indexOf('*') >= 0
                || trimmed.indexOf('?') >= 0
                || trimmed.indexOf('\\') >= 0) {
            throw new BusinessException("远端目录路径不能包含通配符、反斜杠或控制字符");
        }
        String withLeadingSlash = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        String[] parts = withLeadingSlash.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (".".equals(part) || "..".equals(part)) {
                throw new BusinessException("远端目录路径不能包含 . 或 .. 相对路径段");
            }
            sb.append('/').append(part);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    public static String parentDirectoryPath(String normalizedDirectoryPath) {
        String norm = normalizeDirectoryPath(normalizedDirectoryPath);
        if ("/".equals(norm)) {
            return null;
        }
        int lastSlash = norm.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : norm.substring(0, lastSlash);
    }

    private static String normalizeHost(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("请输入 FTP 服务器主机名或 IP");
        }
        String host = raw.trim();
        if (host.length() > MAX_HOST_LENGTH || containsControl(host)
                || host.contains("//") || host.contains("@")
                || host.contains("/") || host.contains("\\")) {
            throw new BusinessException("FTP 主机格式无效，请仅填写主机名或 IP");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9a-fA-F:]+")) {
                throw new BusinessException("IPv6 地址格式无效");
            }
            try {
                InetAddress parsed = InetAddress.getByName(host);
                if (parsed.getAddress().length != 16) {
                    throw new BusinessException("IPv6 地址格式无效");
                }
                return parsed.getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException ex) {
                throw new BusinessException("IPv6 地址格式无效");
            }
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!DNS_NAME.matcher(ascii).matches()) {
                throw new BusinessException("FTP 主机格式无效，请仅填写主机名或 IP");
            }
            return ascii;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("FTP 主机格式无效，请仅填写主机名或 IP");
        }
    }

    private static String validateRemotePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("请输入远端文件路径");
        }
        String path = raw.trim();
        if (path.length() > MAX_REMOTE_PATH_LENGTH || containsControl(path)
                || path.indexOf('*') >= 0 || path.indexOf('?') >= 0
                || path.endsWith("/") || path.endsWith("\\")
                || lastPathSegment(path).isBlank()) {
            throw new BusinessException("远端路径必须指向一个明确文件，不能包含通配符或控制字符");
        }
        return path;
    }

    private static String lastPathSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private static String validateDisplayName(String name) {
        return validateEntryName(name, "文件显示名称");
    }

    private static String validateEntryName(String name, String label) {
        if (name == null || name.isBlank() || name.length() > 255 || containsControl(name)
                || name.contains("/") || name.contains("\\")
                || name.indexOf('*') >= 0 || name.indexOf('?') >= 0
                || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")) {
            throw new BusinessException(label + "不安全，请去掉路径分隔符、通配符、控制字符或尾部空格");
        }
        String stem = name;
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(label + "使用了系统保留名称");
        }
        return name;
    }

    private static String requireCredential(String raw, String label) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_CREDENTIAL_LENGTH || containsControl(raw)) {
            throw new BusinessException(label + "不能为空，也不能包含控制字符");
        }
        return raw;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
    }

    public record ValidatedRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String remotePath,
            FtpAuthMode authMode,
            String username,
            String password,
            String displayName,
            String expectedSha256
    ) {
    }

    public record ValidatedConnectionTestRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String remotePath,
            boolean directoryOnly,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record ValidatedDirectoryRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String remoteDirectoryPath,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record ValidatedCreateDirectoryRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String parentDirectoryPath,
            String directoryName,
            String targetDirectoryPath,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record ValidatedDeleteEntryRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String targetPath,
            String entryName,
            String entryType,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public static ValidatedStreamDownloadRequest validateStreamDownloadRequest(FtpProtocol protocol,
                                                                               String host,
                                                                               int port,
                                                                               String remotePath,
                                                                               FtpAuthMode authMode,
                                                                               String username,
                                                                               String password) {
        if (protocol == null || authMode == null) {
            throw new BusinessException("请选择 FTP/FTPS 协议和认证方式");
        }
        if (port < 1 || port > 65535) {
            throw new BusinessException("FTP 端口必须在 1 到 65535 之间");
        }
        String normalizedHost = normalizeHost(host);
        String normalizedPath = validateRemotePath(remotePath);
        String fileName = validateEntryName(lastPathSegment(normalizedPath), "远端文件名");
        String normalizedUser = null;
        String normalizedPassword = null;
        if (authMode == FtpAuthMode.CREDENTIALS) {
            normalizedUser = requireCredential(username, "FTP 用户名");
            normalizedPassword = requireCredential(password, "FTP 密码");
        }
        return new ValidatedStreamDownloadRequest(protocol, normalizedHost, port, normalizedPath, fileName,
                authMode, normalizedUser, normalizedPassword);
    }

    public static ValidatedMoveItem validateMoveItem(String sourcePath, String targetDirectoryPath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new BusinessException("源条目路径不能为空");
        }
        String normSource = normalizeDirectoryPath(sourcePath);
        if ("/".equals(normSource)) {
            throw new BusinessException("不能移动 FTP 根目录");
        }
        String fileName = validateEntryName(lastPathSegment(normSource), "源条目名称");
        String normTargetDir = normalizeDirectoryPath(targetDirectoryPath);
        if (normTargetDir.equals(normSource) || normTargetDir.startsWith(normSource + "/")) {
            throw new BusinessException("不能将条目移动到自身或其子目录下：" + normSource);
        }
        String targetPath = "/".equals(normTargetDir) ? "/" + fileName : normTargetDir + "/" + fileName;
        return new ValidatedMoveItem(normSource, targetPath, fileName);
    }

    public record ValidatedMoveItem(String sourcePath, String targetPath, String fileName) {
    }

    public record ValidatedUploadRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String remoteDirectoryPath,
            String fileName,
            String targetRemotePath,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }

    public record ValidatedStreamDownloadRequest(
            FtpProtocol protocol,
            String host,
            int port,
            String remotePath,
            String fileName,
            FtpAuthMode authMode,
            String username,
            String password
    ) {
    }
}
