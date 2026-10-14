package com.vvttai.smart_link_shortener.modules.link.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.*;

/**
 * Thành phần bảo vệ SSRF (Server-Side Request Forgery) và kiểm tra tính hợp lệ cơ bản của URL:
 * 1. Scheme/Protocol: chỉ cho phép http/https, chặn scheme nguy hiểm (javascript, file, data...)
 * 2. Chặn self-redirect loop và các domain suffix bị cấm
 * 3. Chặn các dải IP nội bộ, private, loopback, link-local, CGNAT, IPv6 ULA, IPv4-mapped IPv6
 * 4. Resolve DNS một lần duy nhất để chống tấn công DNS rebinding (TOCTOU)
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "javascript:", "data:", "file:", "vbscript:", "blob:", "about:", "ftp:", "ws:", "wss:");

    // CGNAT range: 100.64.0.0/10 -> 100.64.0.0 - 100.127.255.255
    private static final byte CGNAT_FIRST_OCTET = 100;
    private static final byte CGNAT_SECOND_OCTET_MIN = 64;
    private static final byte CGNAT_SECOND_OCTET_MAX = 127;

    @Value("${app.security.allow-localhost-targets:false}")
    private boolean allowLocalhostTargets;

    @Value("${app.security.self-domains:localhost,127.0.0.1,0.0.0.0}")
    private String selfDomainsConfig;

    @Value("${app.security.blocked-domain-suffixes:}")
    private String blockedDomainSuffixesConfig;

    /**
     * Kiểm tra Scheme hợp lệ (chỉ http/https), chặn các scheme nguy hiểm.
     */
    public URI validateSchemeAndStructure(String urlString) {
        String lower = urlString.toLowerCase();
        for (String blocked : BLOCKED_PREFIXES) {
            if (lower.startsWith(blocked)) {
                throw new IllegalArgumentException(
                        "Giao thức không được phép (Chặn " + blocked + " để đảm bảo an toàn)!");
            }
        }

        URI uri;
        try {
            uri = URI.create(urlString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Định dạng URL không hợp lệ: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("Chỉ cho phép liên kết sử dụng giao thức http:// hoặc https://!");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL phải có tên miền (Host) hợp lệ!");
        }

        return uri;
    }

    /**
     * Chặn self-redirect loop và chặn SSRF tới các dải IP Private/Local.
     * Trả về InetAddress đã resolve để tái sử dụng cho reachability check (chống DNS rebinding - TOCTOU).
     */
    public InetAddress validateNotSelfOrPrivateIp(URI uri) {
        String host = uri.getHost().toLowerCase();

        // Sử dụng danh sách self-domains từ cấu hình cố định
        Set<String> selfDomains = new HashSet<>(
                Arrays.stream(selfDomainsConfig.split(","))
                        .map(String::trim)
                        .map(String::toLowerCase)
                        .filter(s -> !s.isBlank())
                        .toList());

        // Chặn trỏ tới chính tên miền của hệ thống
        if (selfDomains.contains(host)) {
            if (!allowLocalhostTargets) {
                throw new IllegalArgumentException(
                        "Không thể rút gọn liên kết trỏ về chính hệ thống (Self-redirect loop)!");
            }
        }

        // Chặn các domain suffix được cấu hình
        if (blockedDomainSuffixesConfig != null && !blockedDomainSuffixesConfig.isBlank()) {
            List<String> blockedSuffixes = Arrays.stream(blockedDomainSuffixesConfig.split(","))
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isBlank())
                    .toList();
            for (String suffix : blockedSuffixes) {
                if (host.equals(suffix) || host.endsWith("." + suffix)) {
                    throw new IllegalArgumentException(
                            "Không thể rút gọn liên kết trỏ tới domain bị chặn: " + suffix + "!");
                }
            }
        }

        // Resolve DNS MỘT LẦN DUY NHẤT và kiểm tra tất cả các IP trả về
        if (!allowLocalhostTargets) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    if (isForbiddenAddress(addr)) {
                        throw new IllegalArgumentException(
                                "Không được phép rút gọn liên kết trỏ tới địa chỉ IP nội bộ hoặc mạng riêng tư!");
                    }
                }
                // Trả về IP đầu tiên (đã kiểm tra an toàn) để dùng cho reachability check
                return addresses[0];
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Tên miền không tồn tại hoặc không thể phân giải DNS: " + host);
            }
        }
        return null;
    }

    /**
     * Kiểm tra toàn diện xem InetAddress có thuộc dải IP bị cấm hay không.
     * Bao gồm: Loopback, AnyLocal, LinkLocal (169.254.169.254), SiteLocal, CGNAT, RFC 791 (0.0.0.0/8), IPv6 ULA, IPv4-mapped IPv6.
     */
    public boolean isForbiddenAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;       // 127.0.0.0/8, ::1
        if (addr.isAnyLocalAddress()) return true;       // 0.0.0.0, ::
        if (addr.isLinkLocalAddress()) return true;      // 169.254.0.0/16, fe80::/10
        if (addr.isSiteLocalAddress()) return true;      // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16

        byte[] raw = addr.getAddress();

        if (raw.length == 4) {
            int firstOctet = raw[0] & 0xFF;
            if (firstOctet == 0) return true; // 0.0.0.0/8

            int secondOctet = raw[1] & 0xFF;
            if (firstOctet == CGNAT_FIRST_OCTET && secondOctet >= (CGNAT_SECOND_OCTET_MIN & 0xFF)
                    && secondOctet <= (CGNAT_SECOND_OCTET_MAX & 0xFF)) {
                return true; // CGNAT 100.64.0.0/10
            }
        } else if (raw.length == 16) {
            int firstByte = raw[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) return true; // Unique Local Address fc00::/7

            // IPv4-mapped IPv6: ::ffff:x.x.x.x
            boolean isIpv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { isIpv4Mapped = false; break; }
            }
            if (isIpv4Mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                byte[] ipv4Bytes = new byte[] { raw[12], raw[13], raw[14], raw[15] };
                try {
                    InetAddress ipv4Addr = InetAddress.getByAddress(ipv4Bytes);
                    return isForbiddenAddress(ipv4Addr);
                } catch (Exception e) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Validate URL đích của redirect response trong reachability check.
     * Chặn redirect tới IP nội bộ, localhost, cloud metadata, v.v.
     */
    public void validateRedirectTarget(String location, URI originalUri) {
        try {
            URI redirectUri = location.startsWith("/") ? originalUri.resolve(location) : URI.create(location);

            String scheme = redirectUri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new IllegalArgumentException(
                        "URL đích redirect sử dụng giao thức không an toàn: " + scheme);
            }

            String redirectHost = redirectUri.getHost();
            if (redirectHost == null || redirectHost.isBlank()) return;

            if (!allowLocalhostTargets) {
                InetAddress[] redirectAddresses = InetAddress.getAllByName(redirectHost);
                for (InetAddress addr : redirectAddresses) {
                    if (isForbiddenAddress(addr)) {
                        log.warn("Blocked redirect to private IP: {} -> {}", originalUri, location);
                        throw new IllegalArgumentException(
                                "URL đích redirect tới địa chỉ IP nội bộ - có thể là tấn công SSRF!");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to validate redirect target: {} -> {}", originalUri, location);
        }
    }
}
