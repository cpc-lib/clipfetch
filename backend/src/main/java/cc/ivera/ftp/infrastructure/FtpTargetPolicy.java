package cc.ivera.ftp.infrastructure;

import cc.ivera.shared.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class FtpTargetPolicy {

    private final FtpProperties properties;
    private final DnsResolver resolver;

    @Autowired
    public FtpTargetPolicy(FtpProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    FtpTargetPolicy(FtpProperties properties, DnsResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    public ResolvedTarget authorize(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new BusinessException("FTP 目标主机或端口无效");
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        boolean exactAllowed = isExactAllowed(normalizedHost, port);
        if (!exactAllowed && !properties.isAllowPublicTargets()) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "FTP 目标未在管理员允许列表中，且公共目标访问未启用");
        }

        InetAddress[] resolved;
        try {
            resolved = resolver.resolve(normalizedHost);
        } catch (UnknownHostException ex) {
            throw new BusinessException("FTP 目标域名无法解析");
        }
        if (resolved == null || resolved.length == 0) {
            throw new BusinessException("FTP 目标域名未解析到可用地址");
        }
        List<InetAddress> addresses = List.copyOf(Arrays.asList(resolved));
        if (!exactAllowed && addresses.stream().anyMatch(FtpTargetPolicy::isBlockedPublicAddress)) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "FTP 目标解析到了私网、保留或非单播地址，已拒绝连接");
        }
        return new ResolvedTarget(normalizedHost, port, addresses, exactAllowed);
    }

    public void verifyConnectedAddress(ResolvedTarget target, InetAddress actual) {
        if (target == null || actual == null || target.addresses().stream().noneMatch(actual::equals)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FTP 控制连接实际地址与安全校验结果不一致");
        }
    }

    private boolean isExactAllowed(String host, int port) {
        String configured = properties.getAllowedTargets();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        for (String raw : configured.split(",")) {
            String candidate = raw.trim().toLowerCase(Locale.ROOT);
            if (candidate.equals(host + ":" + port)
                    || candidate.equals("[" + host + "]:" + port)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBlockedPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return a == 0
                    || a == 10
                    || a == 127
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 0 && c == 0)
                    || (a == 192 && b == 0 && c == 2)
                    || (a == 192 && b == 168)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 224;
        }
        if (address instanceof Inet6Address || bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                try {
                    return isBlockedPublicAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException impossible) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record ResolvedTarget(String host, int port, List<InetAddress> addresses, boolean exactAllowed) {
    }
}
