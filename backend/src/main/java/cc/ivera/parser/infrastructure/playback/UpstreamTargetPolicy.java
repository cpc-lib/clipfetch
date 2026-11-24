package cc.ivera.parser.infrastructure.playback;

import cc.ivera.shared.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 上游地址策略：只允许 http/https，拒绝 loopback、私网、链路本地、ULA、组播与云 metadata，
 * 重定向逐跳校验由代理客户端负责。
 */
@Component
public class UpstreamTargetPolicy {

    private final boolean blockPrivateHosts;

    public UpstreamTargetPolicy(@Value("${app.play.block-private-hosts:true}") boolean blockPrivateHosts) {
        this.blockPrivateHosts = blockPrivateHosts;
    }

    public void validate(URI uri) {
        if (uri == null || uri.getHost() == null) {
            throw new BusinessException("播放地址无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException("播放地址协议不受支持");
        }
        if (!blockPrivateHosts) {
            return;
        }
        String host = uri.getHost();
        if ("localhost".equalsIgnoreCase(host)) {
            throw new BusinessException("播放地址不允许访问本机服务");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException("无法解析播放地址主机：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new BusinessException("播放地址不允许访问内网主机");
            }
        }
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 0.0.0.0/8
            if (first == 0) {
                return true;
            }
            // 100.64.0.0/10 运营商级 NAT
            if (first == 100 && (second & 0xC0) == 64) {
                return true;
            }
            // 198.18.0.0/15 不拦截：Clash 等 TUN 代理的 fake-IP 网段，
            // 直接拦截会导致 CCTV 等 CDN 域名在线播放被误判为内网主机
            return false;
        }
        if (b.length == 16) {
            // IPv4-mapped ::ffff:a.b.c.d
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                int first = b[12] & 0xFF;
                int second = b[13] & 0xFF;
                return first == 0 || (first == 100 && (second & 0xC0) == 64);
            }
            // fc00::/7 唯一本地地址
            return (b[0] & 0xFE) == 0xFC;
        }
        return true;
    }
}
