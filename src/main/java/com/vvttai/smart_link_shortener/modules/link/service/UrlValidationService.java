package com.vvttai.smart_link_shortener.modules.link.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class UrlValidationService {

    private static final Logger log = LoggerFactory.getLogger(UrlValidationService.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "javascript:", "data:", "file:", "vbscript:", "blob:", "about:", "ftp:", "ws:", "wss:");

    // Giới hạn độ dài và ký tự cho custom code
    public static final int MIN_CUSTOM_CODE_LENGTH = 3;
    public static final int MAX_CUSTOM_CODE_LENGTH = 30;
    private static final java.util.regex.Pattern ALPHANUMERIC_PATTERN = java.util.regex.Pattern
            .compile("^[a-zA-Z0-9]+$");

    // Blacklist từ khóa hệ thống / nhạy cảm không cho phép đặt
    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            // Routing & APIs
            "api", "admin", "administrator", "login", "logout", "register", "auth", "oauth", "token",
            "r", "redirect", "dashboard", "analytics", "stats", "links", "link", "users", "user",
            // Infrastructure & Docs
            "swagger", "swagger-ui", "v3", "api-docs", "actuator", "health", "metrics", "info", "env",
            "error", "null", "undefined", "true", "false", "root", "system", "config", "settings",
            // Static files & Common paths
            "static", "public", "assets", "favicon", "favicon.ico", "robots", "robots.txt", "sitemap", "sitemap.xml",
            "terms", "privacy", "contact", "about", "help", "support", "docs", "test", "demo");

    // CGNAT range: 100.64.0.0/10 -> 100.64.0.0 - 100.127.255.255
    private static final byte CGNAT_FIRST_OCTET = 100;
    private static final byte CGNAT_SECOND_OCTET_MIN = 64;
    private static final byte CGNAT_SECOND_OCTET_MAX = 127;

    private final ObjectMapper objectMapper;

    // HttpClient dùng cho Safe Browsing API (không cần custom DNS resolver)
    private final HttpClient safeBrowsingClient;

    @Value("${google.safebrowsing.api-key:}")
    private String googleSafeBrowsingApiKey;

    @Value("${app.security.check-reachability:true}")
    private boolean checkReachability;

    @Value("${app.security.allow-localhost-targets:false}")
    private boolean allowLocalhostTargets;

    @Value("${app.security.self-domains:localhost,127.0.0.1,0.0.0.0}")
    private String selfDomainsConfig;

    // Danh sách domain suffix bị chặn (cấu hình, thay vì hard-code trycloudflare.com)
    @Value("${app.security.blocked-domain-suffixes:}")
    private String blockedDomainSuffixesConfig;

    public UrlValidationService() {
        this.safeBrowsingClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * Kiểm tra toàn diện tính hợp lệ và an toàn của URL đích:
     * 1. Protocol / Scheme (Chỉ cho phép http/https, chặn javascript:, data:,
     * file:...)
     * 2. Chặn tự-trỏ-vào-chính-mình (Self-redirect loop) & Chặn SSRF tới IP nội bộ
     * 3. Kiểm tra mã độc & lừa đảo với Google Safe Browsing API
     * 4. Kiểm tra khả năng truy cập (Reachability ping) - sử dụng IP đã resolve
     * để chống DNS rebinding
     */
    public void validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL đích không được để trống!");
        }

        String trimmedUrl = urlString.trim();

        // 1. Kiểm tra Scheme & Protocol
        URI uri = validateSchemeAndStructure(trimmedUrl);

        // 2. Chặn Self-redirect loop, chặn IP nội bộ (SSRF), và resolve DNS một lần duy nhất
        InetAddress resolvedAddress = validateNotSelfOrPrivateIp(uri);

        // 3. Kiểm tra Google Safe Browsing (Malware / Phishing)
        checkGoogleSafeBrowsing(trimmedUrl);

        // 4. Kiểm tra Reachability (HEAD / GET request) - dùng IP đã resolve để chống DNS rebinding
        if (checkReachability) {
            checkReachability(uri, resolvedAddress);
        }
    }

    /**
     * Kiểm tra Scheme hợp lệ (chỉ http/https), chặn các scheme nguy hiểm
     */
    private URI validateSchemeAndStructure(String urlString) {
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
     * Trả về InetAddress đã resolve để tái sử dụng cho reachability check
     * (chống DNS rebinding - TOCTOU).
     */
    private InetAddress validateNotSelfOrPrivateIp(URI uri) {
        String host = uri.getHost().toLowerCase();

        // Sử dụng danh sách self-domains từ cấu hình cố định (KHÔNG tin vào header client gửi)
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

        // Chặn các domain suffix được cấu hình (thay vì hard-code trycloudflare.com)
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
        // Sau đó tái sử dụng IP đã resolve cho reachability check -> chống DNS rebinding
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
     * Bao gồm tất cả các dải private/reserved theo RFC:
     * - Loopback (127.0.0.0/8, ::1)
     * - Link-local (169.254.0.0/16, fe80::/10) - bao gồm cả 169.254.169.254 (cloud metadata)
     * - Site-local / Private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
     * - CGNAT / Shared Address Space (100.64.0.0/10 - RFC 6598)
     * - "This network" (0.0.0.0/8)
     * - Any local address (0.0.0.0, ::)
     * - IPv6 Unique Local Address (fc00::/7 - RFC 4193)
     * - IPv4-mapped IPv6 (::ffff:x.x.x.x) - kiểm tra IP nội bộ bên trong
     */
    static boolean isForbiddenAddress(InetAddress addr) {
        // Các check cơ bản từ JDK
        if (addr.isLoopbackAddress()) return true;       // 127.0.0.0/8, ::1
        if (addr.isAnyLocalAddress()) return true;       // 0.0.0.0, ::
        if (addr.isLinkLocalAddress()) return true;      // 169.254.0.0/16, fe80::/10 (bao gồm 169.254.169.254)
        if (addr.isSiteLocalAddress()) return true;      // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16

        byte[] raw = addr.getAddress();

        if (raw.length == 4) {
            // === IPv4 checks ===
            int firstOctet = raw[0] & 0xFF;

            // 0.0.0.0/8 - "This network" (RFC 791)
            if (firstOctet == 0) return true;

            // 100.64.0.0/10 - CGNAT / Shared Address Space (RFC 6598)
            int secondOctet = raw[1] & 0xFF;
            if (firstOctet == CGNAT_FIRST_OCTET && secondOctet >= (CGNAT_SECOND_OCTET_MIN & 0xFF)
                    && secondOctet <= (CGNAT_SECOND_OCTET_MAX & 0xFF)) {
                return true;
            }
        } else if (raw.length == 16) {
            // === IPv6 checks ===

            // fc00::/7 - Unique Local Address (RFC 4193)
            // Bao gồm fc00::/8 và fd00::/8
            int firstByte = raw[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) return true;

            // IPv4-mapped IPv6: ::ffff:x.x.x.x
            // Bytes 0-9 = 0, bytes 10-11 = 0xFF, bytes 12-15 = IPv4 address
            boolean isIpv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { isIpv4Mapped = false; break; }
            }
            if (isIpv4Mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                // Trích xuất phần IPv4 và kiểm tra đệ quy
                byte[] ipv4Bytes = new byte[] { raw[12], raw[13], raw[14], raw[15] };
                try {
                    InetAddress ipv4Addr = InetAddress.getByAddress(ipv4Bytes);
                    return isForbiddenAddress(ipv4Addr);
                } catch (Exception e) {
                    return true; // An toàn: nếu không parse được thì chặn
                }
            }
        }

        return false;
    }

    /**
     * Tích hợp Google Safe Browsing Lookup API v4 để kiểm tra Malware, Phishing
     */
    private void checkGoogleSafeBrowsing(String urlString) {
        if (googleSafeBrowsingApiKey == null || googleSafeBrowsingApiKey.isBlank()) {
            log.debug("Google Safe Browsing check skipped (API key not configured)");
            return;
        }

        try {
            String endpoint = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key="
                    + googleSafeBrowsingApiKey.trim();

            String requestBody = """
                    {
                      "client": {
                        "clientId": "smart-link-shortener",
                        "clientVersion": "1.0.0"
                      },
                      "threatInfo": {
                        "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
                        "platformTypes": ["ANY_PLATFORM"],
                        "threatEntryTypes": ["URL"],
                        "threatEntries": [
                          {"url": "%s"}
                        ]
                      }
                    }
                    """
                    .formatted(urlString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = safeBrowsingClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode matches = root.get("matches");
                if (matches != null && matches.isArray() && !matches.isEmpty()) {
                    String threatType = matches.get(0).path("threatType").asText("MALWARE/PHISHING");
                    log.warn("Blocked dangerous URL via Google Safe Browsing: url={}, threat={}", urlString,
                            threatType);
                    throw new IllegalArgumentException(
                            "URL bị cảnh báo nguy hiểm (" + threatType + " theo Google Safe Browsing)!");
                }
            } else {
                log.warn("Google Safe Browsing API returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error calling Google Safe Browsing API: {}. Fallback allow.", e.getMessage());
        }
    }

    /**
     * Kiểm tra khả năng phản hồi của URL đích (Reachability check).
     *
     * SSRF hardening:
     * - Sử dụng IP đã resolve từ bước validate (chống DNS rebinding / TOCTOU)
     * - Dùng Redirect.NEVER (chống redirect tới IP nội bộ)
     * - Kết nối thẳng vào IP đã kiểm tra, gửi Host header gốc
     *
     * @param originalUri     URI gốc (dùng để lấy scheme, host header, path)
     * @param resolvedAddress IP đã resolve và kiểm tra an toàn (null nếu allowLocalhostTargets=true)
     */
    private void checkReachability(URI originalUri, InetAddress resolvedAddress) {
        try {
            // Xây dựng URI kết nối thẳng vào IP đã resolve (chống DNS rebinding)
            URI connectUri;
            if (resolvedAddress != null) {
                String ipStr = resolvedAddress.getHostAddress();
                // Nếu là IPv6, cần bọc trong brackets
                if (resolvedAddress instanceof java.net.Inet6Address) {
                    ipStr = "[" + ipStr + "]";
                }
                int port = originalUri.getPort();
                if (port == -1) {
                    port = "https".equalsIgnoreCase(originalUri.getScheme()) ? 443 : 80;
                }
                String path = originalUri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                String query = originalUri.getRawQuery();
                String connectUrl = originalUri.getScheme() + "://" + ipStr + ":" + port + path;
                if (query != null) connectUrl += "?" + query;
                connectUri = URI.create(connectUrl);
            } else {
                connectUri = originalUri;
            }

            // Tạo HttpClient KHÔNG follow redirect (chống redirect-based SSRF)
            HttpClient reachabilityClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            // Thử gửi HEAD request trước (nhẹ và nhanh)
            HttpRequest.Builder headBuilder = HttpRequest.newBuilder()
                    .uri(connectUri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                    .timeout(Duration.ofSeconds(3));

            // Gửi Host header gốc (cần thiết cho virtual hosting khi kết nối bằng IP)
            if (resolvedAddress != null) {
                String originalHost = originalUri.getHost();
                int originalPort = originalUri.getPort();
                if (originalPort != -1 && originalPort != 80 && originalPort != 443) {
                    originalHost += ":" + originalPort;
                }
                headBuilder.header("Host", originalHost);
            }

            HttpResponse<Void> response = reachabilityClient.send(
                    headBuilder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            // Nếu server redirect (3xx), validate URL đích của redirect
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null && !location.isBlank()) {
                    validateRedirectTarget(location, originalUri);
                }
                // Redirect 3xx vẫn chấp nhận - server sống (chỉ cần biết server sống)
                return;
            }

            // Nếu server không hỗ trợ HEAD (405 Method Not Allowed), thử gửi GET
            if (status == 405) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                        .uri(connectUri)
                        .GET()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                        .timeout(Duration.ofSeconds(3));

                if (resolvedAddress != null) {
                    String originalHost = originalUri.getHost();
                    int originalPort = originalUri.getPort();
                    if (originalPort != -1 && originalPort != 80 && originalPort != 443) {
                        originalHost += ":" + originalPort;
                    }
                    getBuilder.header("Host", originalHost);
                }

                response = reachabilityClient.send(getBuilder.build(), HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();

                // Validate redirect từ GET response
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location != null && !location.isBlank()) {
                        validateRedirectTarget(location, originalUri);
                    }
                }
            }

            // Chấp nhận tất cả status code hợp lệ từ web server (kể cả 401/403 do chặn bot
            // nhưng domain vẫn sống)
            if (status >= 500 && status <= 504) {
                log.warn("Target URL server error status {}: {}", status, originalUri);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Reachability check failed for URL {}: {}", originalUri, e.getMessage());
            throw new IllegalArgumentException(
                    "Không thể kết nối đến URL đích (Máy chủ không phản hồi hoặc tên miền không hợp lệ)!");
        }
    }

    /**
     * Validate URL đích của redirect response.
     * Chặn redirect tới IP nội bộ, localhost, cloud metadata, v.v.
     */
    private void validateRedirectTarget(String location, URI originalUri) {
        try {
            URI redirectUri;
            // Xử lý redirect tương đối
            if (location.startsWith("/")) {
                redirectUri = originalUri.resolve(location);
            } else {
                redirectUri = URI.create(location);
            }

            // Kiểm tra scheme
            String scheme = redirectUri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new IllegalArgumentException(
                        "URL đích redirect sử dụng giao thức không an toàn: " + scheme);
            }

            String redirectHost = redirectUri.getHost();
            if (redirectHost == null || redirectHost.isBlank()) return;

            // Resolve DNS của redirect target và kiểm tra
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
            // Không chặn nếu không parse được redirect URL
        }
    }

    /**
     * Kiểm tra tính hợp lệ của custom short code:
     * 1. Độ dài: từ 3 đến 30 ký tự
     * 2. Ký tự: chỉ cho phép chữ và số (alphanumeric: a-z, A-Z, 0-9), không có ký
     * tự đặc biệt
     * 3. Blacklist: không được trùng với các từ khóa hệ thống (admin, api, login,
     * etc.)
     */
    public void validateCustomCode(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            return;
        }

        String code = customCode.trim();

        // 1. Kiểm tra độ dài
        if (code.length() < MIN_CUSTOM_CODE_LENGTH || code.length() > MAX_CUSTOM_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "Mã rút gọn tùy chỉnh phải có độ dài từ " + MIN_CUSTOM_CODE_LENGTH + " đến "
                            + MAX_CUSTOM_CODE_LENGTH + " ký tự!");
        }

        // 2. Kiểm tra ký tự cho phép (chỉ alphanumeric)
        if (!ALPHANUMERIC_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Mã rút gọn tùy chỉnh chỉ được chứa các ký tự chữ và số (a-z, A-Z, 0-9), không chứa dấu cách hoặc ký tự đặc biệt!");
        }

        // 3. Kiểm tra blacklist từ khóa hệ thống
        if (RESERVED_KEYWORDS.contains(code.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Mã rút gọn '" + code + "' là từ khóa hệ thống dành riêng, vui lòng chọn mã khác!");
        }
    }
}
