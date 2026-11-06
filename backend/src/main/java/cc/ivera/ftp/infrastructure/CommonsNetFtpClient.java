package cc.ivera.ftp.infrastructure;

import cc.ivera.ftp.domain.FtpAuthMode;
import cc.ivera.ftp.domain.FtpProtocol;
import cc.ivera.ftp.domain.FtpRequestValidator;
import cc.ivera.shared.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CommonsNetFtpClient {

    private final FtpProperties properties;
    private final FtpTargetPolicy targetPolicy;

    public Session connect(ConnectionRequest request) throws IOException {
        if (!properties.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FTP 下载功能未启用或配置不完整");
        }
        FtpTargetPolicy.ResolvedTarget target = targetPolicy.authorize(request.host(), request.port());
        FTPClient client = createConfiguredClient(request.protocol());
        try {
            if (client instanceof VerifiedFtpsClient ftps) {
                ftps.setExpectedHostname(request.host());
            }
            client.connect(target.addresses().getFirst(), target.port());
            targetPolicy.verifyConnectedAddress(target, client.getRemoteAddress());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IOException("FTP 控制连接被服务器拒绝，回复码 " + client.getReplyCode());
            }
            String username = request.authMode() == FtpAuthMode.ANONYMOUS
                    ? properties.getAnonymousUsername() : request.username();
            String password = request.authMode() == FtpAuthMode.ANONYMOUS
                    ? properties.getAnonymousPassword() : request.password();
            if (!client.login(username, password)) {
                throw new FtpAuthenticationException("FTP 服务器拒绝登录，回复码 " + client.getReplyCode());
            }
            if (client instanceof FTPSClient ftps) {
                ftps.execPBSZ(0);
                ftps.execPROT("P");
            }
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IOException("FTP 服务器不接受二进制传输模式，回复码 " + client.getReplyCode());
            }
            client.enterLocalPassiveMode();
            return new Session(client);
        } catch (IOException | RuntimeException ex) {
            disconnectQuietly(client);
            throw ex;
        }
    }

    FTPClient createConfiguredClient(FtpProtocol protocol) {
        if (protocol == FtpProtocol.FTP && !properties.isAllowPlaintext()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "普通 FTP 明文传输未由管理员启用，请使用显式 FTPS");
        }
        FTPClient client = switch (protocol) {
            case FTP -> new FTPClient();
            case FTPS_EXPLICIT -> createFtpsClient();
        };
        // 控制通道默认 ISO-8859-1，中文文件名会被服务端替换成 '?'，必须显式 UTF-8（连接前设置）
        client.setControlEncoding(StandardCharsets.UTF_8.name());
        client.setConnectTimeout(properties.getConnectTimeoutMs());
        client.setDefaultTimeout(properties.getReadTimeoutMs());
        client.setDataTimeout(Duration.ofMillis(properties.getTransferIdleTimeoutMs()));
        client.setRemoteVerificationEnabled(true);
        client.setUseEPSVwithIPv4(true);
        client.setIpAddressFromPasvResponse(false);
        client.setBufferSize(properties.getFileBufferBytes());
        return client;
    }

    private FTPSClient createFtpsClient() {
        FTPSClient client = new VerifiedFtpsClient(sslContext());
        client.setEndpointCheckingEnabled(true);
        return client;
    }

    static final class VerifiedFtpsClient extends FTPSClient {

        private String expectedHostname;

        private VerifiedFtpsClient(SSLContext context) {
            super(false, context);
        }

        private void setExpectedHostname(String expectedHostname) {
            this.expectedHostname = expectedHostname;
        }

        @Override
        protected void _connectAction_() throws IOException {
            if (expectedHostname == null || expectedHostname.isBlank()) {
                throw new SSLHandshakeException("FTPS 主机名未配置");
            }
            _hostname_ = expectedHostname;
            super._connectAction_();
        }

        @Override
        protected void _prepareDataSocket_(Socket socket) throws IOException {
            if (socket != null && !(socket instanceof SSLSocket)) {
                throw new SSLHandshakeException("FTPS 数据通道未使用 TLS");
            }
        }

        @Override
        protected Socket _openDataConnection_(String command, String arg) throws IOException {
            Socket socket = super._openDataConnection_(command, arg);
            if (socket == null) {
                return null;
            }
            if (!(socket instanceof SSLSocket sslSocket)) {
                socket.close();
                throw new SSLHandshakeException("FTPS 数据通道未使用 TLS");
            }
            if (!verifyDataHostname(expectedHostname, sslSocket.getSession())) {
                socket.close();
                throw new SSLHandshakeException("FTPS 数据通道证书主机名不匹配");
            }
            return socket;
        }
    }

    static boolean verifyDataHostname(String expectedHostname, SSLSession session) {
        if (expectedHostname == null || session == null) {
            return false;
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            return certificates.length > 0 && certificates[0] instanceof X509Certificate certificate
                    && matchesCertificate(expectedHostname, certificate);
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean matchesCertificate(String expectedHostname, X509Certificate certificate)
            throws CertificateParsingException {
        String host = expectedHostname.toLowerCase(Locale.ROOT);
        boolean ipAddress = host.indexOf(':') >= 0 || host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
        Collection<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
        boolean hasRelevantAlternativeName = false;
        if (alternativeNames != null) {
            for (List<?> alternativeName : alternativeNames) {
                if (alternativeName == null || alternativeName.size() < 2
                        || !(alternativeName.getFirst() instanceof Integer type)
                        || !(alternativeName.get(1) instanceof String value)) {
                    continue;
                }
                if (ipAddress && type == 7) {
                    hasRelevantAlternativeName = true;
                    try {
                        if (Arrays.equals(InetAddress.getByName(host).getAddress(),
                                InetAddress.getByName(value).getAddress())) {
                            return true;
                        }
                    } catch (IOException ignored) {
                    }
                } else if (!ipAddress && type == 2) {
                    hasRelevantAlternativeName = true;
                    if (matchesDnsName(host, value.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        if (hasRelevantAlternativeName || ipAddress) {
            return false;
        }
        try {
            for (Rdn rdn : new LdapName(certificate.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())
                        && matchesDnsName(host, String.valueOf(rdn.getValue()).toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (InvalidNameException ignored) {
        }
        return false;
    }

    private static boolean matchesDnsName(String host, String certificateName) {
        if (host.equals(certificateName)) {
            return true;
        }
        if (!certificateName.startsWith("*.") || certificateName.indexOf('*', 1) >= 0) {
            return false;
        }
        String suffix = certificateName.substring(1);
        if (!host.endsWith(suffix)) {
            return false;
        }
        String leftmostLabel = host.substring(0, host.length() - suffix.length());
        return !leftmostLabel.isEmpty() && leftmostLabel.indexOf('.') < 0;
    }

    private SSLContext sslContext() {
        String truststorePath = properties.getTruststorePath();
        if (truststorePath == null || truststorePath.isBlank()) {
            try {
                return SSLContext.getDefault();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("无法加载 JVM 默认 TLS 信任配置", ex);
            }
        }
        char[] password = properties.getTruststorePassword() == null
                ? new char[0] : properties.getTruststorePassword().toCharArray();
        try (InputStream input = Files.newInputStream(Path.of(truststorePath).toAbsolutePath().normalize())) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(input, password);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("FTP 专用 TLS truststore 加载失败", ex);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void disconnectQuietly(FTPClient client) {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            client.disconnect();
        } catch (IOException ignored) {
        }
    }

    public record ConnectionRequest(FtpProtocol protocol, String host, int port, FtpAuthMode authMode,
                                    String username, String password) {
    }

    public record RemoteIdentity(Long size, Instant modifiedAt, String uniqueId) {
        public boolean hasResumeIdentity() {
            return modifiedAt != null || (uniqueId != null && !uniqueId.isBlank());
        }
    }

    public record RemoteEntry(String name, String path, String type, Long size, Instant modifiedAt) {
    }

    public static final class Session implements AutoCloseable {

        private final FTPClient client;
        private boolean transferOpen;
        private volatile InputStream activeInput;

        private Session(FTPClient client) {
            this.client = client;
        }

        public RemoteIdentity inspect(String remotePath) throws IOException {
            FTPFile facts = client.mlistFile(remotePath);
            Long size = facts != null && facts.isValid() ? facts.getSize() : parseLong(client.getSize(remotePath));
            Instant modified = facts != null && facts.getTimestamp() != null
                    ? facts.getTimestampInstant() : modificationTime(remotePath);
            return new RemoteIdentity(size, modified, null);
        }

        public boolean checkDirectoryExists(String directoryPath) throws IOException {
            String normalizedDir = (directoryPath == null || directoryPath.isBlank()) ? "/" : directoryPath;
            return client.changeWorkingDirectory(normalizedDir);
        }

        public List<RemoteEntry> listDirectory(String directoryPath) throws IOException {
            String normalizedDir = (directoryPath == null || directoryPath.isBlank()) ? "/" : directoryPath;
            if (!client.changeWorkingDirectory(normalizedDir)) {
                throw new IOException("远端目录不存在或无权访问，回复码 " + client.getReplyCode());
            }
            FTPFile[] rawFiles = null;
            try {
                FTPFile[] mlsd = client.mlistDir();
                if (mlsd != null && FTPReply.isPositiveCompletion(client.getReplyCode())) {
                    rawFiles = mlsd;
                }
            } catch (IOException ignored) {
                // 服务器不支持 MLSD 时降级为 LIST
            }
            if (rawFiles == null) {
                rawFiles = client.listFiles();
                if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                    throw new IOException("FTP 服务器拒绝读取目录列表，回复码 " + client.getReplyCode());
                }
            }
            List<RemoteEntry> entries = new ArrayList<>();
            if (rawFiles != null) {
                for (FTPFile file : rawFiles) {
                    if (file == null || !file.isValid()) {
                        continue;
                    }
                    String name = file.getName();
                    if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)
                            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                        continue;
                    }
                    String childPath = "/".equals(normalizedDir) ? "/" + name : normalizedDir + "/" + name;
                    String type = file.isDirectory() ? "DIRECTORY" : (file.isSymbolicLink() ? "SYMLINK" : "FILE");
                    Long size = file.isDirectory() ? null : (file.getSize() >= 0 ? file.getSize() : null);
                    Instant modified = file.getTimestamp() != null ? file.getTimestampInstant() : null;
                    entries.add(new RemoteEntry(name, childPath, type, size, modified));
                }
            }
            entries.sort(Comparator
                    .comparing((RemoteEntry e) -> !"DIRECTORY".equals(e.type()))
                    .thenComparing(RemoteEntry::name, String.CASE_INSENSITIVE_ORDER));
            return entries;
        }

        public void makeDirectory(String targetDirectoryPath) throws IOException {
            if (!client.makeDirectory(targetDirectoryPath)) {
                throw new IOException("FTP 服务器拒绝创建文件夹，回复码 " + client.getReplyCode());
            }
        }

        public void makeDirectories(String directoryPath) throws IOException {
            String norm = FtpRequestValidator.normalizeDirectoryPath(directoryPath);
            if ("/".equals(norm)) {
                return;
            }
            String[] segments = norm.split("/");
            StringBuilder current = new StringBuilder();
            for (String seg : segments) {
                if (seg == null || seg.isEmpty()) {
                    continue;
                }
                current.append('/').append(seg);
                String path = current.toString();
                if (!client.changeWorkingDirectory(path)) {
                    client.makeDirectory(path);
                }
            }
        }

        public void rename(String fromPath, String toPath) throws IOException {
            if (fromPath == null || toPath == null || fromPath.equals(toPath)) {
                return;
            }
            if (!client.rename(fromPath, toPath)) {
                throw new IOException("FTP 服务器拒绝移动或重命名条目 [" + fromPath + " -> " + toPath + "]，回复码 " + client.getReplyCode());
            }
        }

        public void deleteFile(String remoteFilePath) throws IOException {
            if (!client.deleteFile(remoteFilePath)) {
                throw new IOException("FTP 服务器拒绝删除文件，回复码 " + client.getReplyCode());
            }
        }

        private static final int MAX_DELETE_DEPTH = 16;
        private static final int MAX_DELETE_ENTRIES = 2000;

        public void removeDirectory(String remoteDirectoryPath) throws IOException {
            if (remoteDirectoryPath == null || remoteDirectoryPath.isBlank()
                    || "/".equals(remoteDirectoryPath.trim())) {
                throw new IOException("不能删除 FTP 根目录");
            }
            if (client.removeDirectory(remoteDirectoryPath)) {
                return;
            }
            int[] deletedCount = new int[]{0};
            removeDirectoryRecursive(remoteDirectoryPath, 0, deletedCount);
        }

        private void removeDirectoryRecursive(String dirPath, int depth, int[] deletedCount) throws IOException {
            if (depth > MAX_DELETE_DEPTH) {
                throw new IOException("远端目录层级过深（超过 " + MAX_DELETE_DEPTH + " 层），已中止删除");
            }
            List<RemoteEntry> children = listDirectory(dirPath);
            for (RemoteEntry child : children) {
                if (++deletedCount[0] > MAX_DELETE_ENTRIES) {
                    throw new IOException("远端目录内条目过多（超过 " + MAX_DELETE_ENTRIES + " 项），已中止删除");
                }
                if ("DIRECTORY".equals(child.type())) {
                    removeDirectoryRecursive(child.path(), depth + 1, deletedCount);
                } else {
                    deleteFile(child.path());
                }
            }
            String parentDir = FtpRequestValidator.parentDirectoryPath(dirPath);
            client.changeWorkingDirectory(parentDir != null ? parentDir : "/");
            if (!client.removeDirectory(dirPath)) {
                throw new IOException("FTP 服务器拒绝删除文件夹，回复码 " + client.getReplyCode());
            }
        }

        public long store(String remoteFilePath, InputStream localInput, long maxBytes) throws IOException {
            client.setRestartOffset(0L);
            OutputStream output = client.storeFileStream(remoteFilePath);
            if (output == null) {
                throw new IOException("FTP 服务器拒绝上传文件，回复码 " + client.getReplyCode());
            }
            long uploaded = 0L;
            byte[] buffer = new byte[Math.max(4096, client.getBufferSize() > 0 ? client.getBufferSize() : 65536)];
            boolean streamClosed = false;
            try {
                int read;
                while ((read = localInput.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    uploaded += read;
                    if (maxBytes > 0 && uploaded > maxBytes) {
                        throw new IOException("上传文件超过管理员配置的大小上限");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.close();
                streamClosed = true;
            } finally {
                if (!streamClosed) {
                    try {
                        output.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (!client.completePendingCommand()) {
                throw new IOException("FTP 文件上传未正常完成，回复码 " + client.getReplyCode());
            }
            return uploaded;
        }

        public InputStream retrieve(String remotePath, long offset) throws IOException {
            if (offset < 0) {
                throw new IllegalArgumentException("FTP 续传偏移不能为负数");
            }
            client.setRestartOffset(offset);
            InputStream input = client.retrieveFileStream(remotePath);
            if (input == null) {
                throw new IOException("FTP 服务器拒绝文件读取，回复码 " + client.getReplyCode());
            }
            activeInput = input;
            transferOpen = true;
            return input;
        }

        public void completeTransfer() throws IOException {
            if (!transferOpen) {
                throw new IllegalStateException("FTP 数据传输尚未开始");
            }
            transferOpen = false;
            activeInput = null;
            if (!client.completePendingCommand()) {
                throw new IOException("FTP 文件传输未正常完成，回复码 " + client.getReplyCode());
            }
        }

        public void abort() {
            InputStream input = activeInput;
            activeInput = null;
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ignored) {
            }
            disconnectQuietly(client);
        }

        @Override
        public void close() {
            if (!client.isConnected()) {
                return;
            }
            try {
                client.logout();
            } catch (IOException ignored) {
            } finally {
                disconnectQuietly(client);
            }
        }

        private static Long parseLong(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private Instant modificationTime(String remotePath) {
            try {
                return client.mdtmInstant(remotePath);
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
    }

    public static class FtpAuthenticationException extends IOException {
        public FtpAuthenticationException(String message) {
            super(message);
        }
    }
}
