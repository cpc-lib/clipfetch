#pragma once

#include <arpa/inet.h>

#include <string>

namespace worker {

inline bool blocked_ipv4(uint32_t address) {
    const uint32_t a = (address >> 24) & 0xFF;
    const uint32_t b = (address >> 16) & 0xFF;
    const uint32_t c = (address >> 8) & 0xFF;

    if (a == 0 || a == 10 || a == 127) return true;
    if (a == 169 && b == 254) return true;
    if (a == 172 && b >= 16 && b <= 31) return true;
    if (a == 192 && b == 168) return true;
    if (a == 100 && b >= 64 && b <= 127) return true;
    if (a == 192 && b == 0 && c == 0) return true;
    if (a == 192 && b == 0 && c == 2) return true;
    if (a == 198 && (b == 18 || b == 19)) return true;
    if (a == 198 && b == 51 && c == 100) return true;
    if (a == 203 && b == 0 && c == 113) return true;
    if (a >= 224) return true;
    return false;
}

inline bool blocked_ipv6(const in6_addr& address) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(&address);
    bool all_zero = true;
    for (int i = 0; i < 16; ++i) {
        if (bytes[i] != 0) {
            all_zero = false;
            break;
        }
    }
    if (all_zero) return true;
    bool only_loopback = bytes[15] == 1;
    for (int i = 0; i < 15 && only_loopback; ++i) {
        if (bytes[i] != 0) {
            only_loopback = false;
        }
    }
    if (only_loopback) return true;

    // IPv4-mapped ::ffff:a.b.c.d — evaluate the embedded IPv4 address.
    if (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0 &&
        bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0 &&
        bytes[8] == 0 && bytes[9] == 0 && bytes[10] == 0xFF && bytes[11] == 0xFF) {
        const uint32_t v4 = (static_cast<uint32_t>(bytes[12]) << 24) |
                            (static_cast<uint32_t>(bytes[13]) << 16) |
                            (static_cast<uint32_t>(bytes[14]) << 8) |
                            static_cast<uint32_t>(bytes[15]);
        return blocked_ipv4(v4);
    }

    if ((bytes[0] & 0xFE) == 0xFC) return true;  // fc00::/7 unique local
    if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) return true;  // fe80::/10
    if ((bytes[0] & 0xFF) == 0xFF) return true;  // ff00::/8 multicast
    return false;
}

// Default-deny for loopback, link-local, RFC1918, cloud metadata and reserved ranges.
inline bool blocked_endpoint(const std::string& ip) {
    in6_addr v6{};
    if (inet_pton(AF_INET6, ip.c_str(), &v6) == 1) {
        return blocked_ipv6(v6);
    }
    in_addr v4{};
    if (inet_pton(AF_INET, ip.c_str(), &v4) == 1) {
        return blocked_ipv4(ntohl(v4.s_addr));
    }
    // Unparseable endpoints are treated as blocked (fail closed).
    return true;
}

}  // namespace worker
