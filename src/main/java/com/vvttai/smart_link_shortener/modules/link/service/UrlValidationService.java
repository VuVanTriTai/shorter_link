package com.vvttai.smart_link_shortener.modules.link.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
public class UrlValidationService {

    private static final Logger log = LoggerFactory.getLogger(UrlValidationService.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "javascript:", "data:", "file:", "vbscript:", "blob:", "about:", "ftp:", "ws:", "wss:"
    );

    // Giới hạn độ dài và ký tự cho custom code
    public static final int MIN_CUSTOM_CODE_LENGTH = 3;
    public static final int MAX_CUSTOM_CODE_LENGTH = 30;
    private static final java.util.regex.Pattern ALPHANUMERIC_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9]+$");

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
            "terms", "privacy", "contact", "about", "help", "support", "docs", "test", "demo"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${google.safebrowsing.api-key:}")
    private String googleSafeBrowsingApiKey;

    @Value("${app.security.check-reachability:true}")
    private boolean checkReachability;

    @Value("${app.security.allow-localhost-targets:false}")
    private boolean allowLocalhostTargets;

    @Value("${app.security.self-domains:localhost,127.0.0.1,0.0.0.0}")
    private String selfDomainsConfig;

    public UrlValidationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * Kiểm tra toàn diện tính hợp lệ và an toàn của URL đích:
     * 1. Protocol / Scheme (Chỉ cho phép http/https, chặn javascript:, data:, file:...)
     * 2. Chặn tự-trỏ-vào-chính-mình (Self-redirect loop) & Chặn SSRF tới IP nội bộ
     * 3. Kiểm tra mã độc & lừa đảo với Google Safe Browsing API
     * 4. Kiểm tra khả năng truy cập (Reachability ping)
     */
    public void validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL đích không được để trống!");
        }

        String trimmedUrl = urlString.trim();

        // 1. Kiểm tra Scheme & Protocol
        URI uri = validateSchemeAndStructure(trimmedUrl);

        // 2. Chặn Self-redirect loop và chặn IP nội bộ (SSRF)
        validateNotSelfOrPrivateIp(uri);

        // 3. Kiểm tra Google Safe Browsing (Malware / Phishing)
        checkGoogleSafeBrowsing(trimmedUrl);

        // 4. Kiểm tra Reachability (HEAD / GET request)
        if (checkReachability) {
            checkReachability(uri);
        }
    }

    /**
     * Kiểm tra Scheme hợp lệ (chỉ http/https), chặn các scheme nguy hiểm
     */
    private URI validateSchemeAndStructure(String urlString) {
        String lower = urlString.toLowerCase();
        for (String blocked : BLOCKED_PREFIXES) {
            if (lower.startsWith(blocked)) {
                throw new IllegalArgumentException("Giao thức không được phép (Chặn " + blocked + " để đảm bảo an toàn)!");
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
     * Chặn self-redirect loop và chặn SSRF tới các dải IP Private/Local
     */
    private void validateNotSelfOrPrivateIp(URI uri) {
        String host = uri.getHost().toLowerCase();
        List<String> selfDomains = Arrays.stream(selfDomainsConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .toList();

        // Chặn trỏ tới chính tên miền của hệ thống
        if (selfDomains.contains(host)) {
            if (!allowLocalhostTargets) {
                throw new IllegalArgumentException("Không thể rút gọn liên kết trỏ về chính hệ thống (Self-redirect loop)!");
            }
        }

        // Kiểm tra phân giải IP để chặn tấn công SSRF vào mạng nội bộ
        if (!allowLocalhostTargets) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                        throw new IllegalArgumentException("Không được phép rút gọn liên kết trỏ tới địa chỉ IP nội bộ hoặc mạng riêng tư!");
                    }
                    // Chặn dải metadata AWS/Cloud 169.254.169.254
                    if ("169.254.169.254".equals(addr.getHostAddress())) {
                        throw new IllegalArgumentException("Không được phép trỏ tới địa chỉ Cloud Metadata!");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Tên miền không tồn tại hoặc không thể phân giải DNS: " + host);
            }
        }
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
            String endpoint = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + googleSafeBrowsingApiKey.trim();

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
                """.formatted(urlString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode matches = root.get("matches");
                if (matches != null && matches.isArray() && !matches.isEmpty()) {
                    String threatType = matches.get(0).path("threatType").asText("MALWARE/PHISHING");
                    log.warn("Blocked dangerous URL via Google Safe Browsing: url={}, threat={}", urlString, threatType);
                    throw new IllegalArgumentException("URL bị cảnh báo nguy hiểm (" + threatType + " theo Google Safe Browsing)!");
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
     * Kiểm tra khả năng phản hồi của URL đích (Reachability check)
     */
    private void checkReachability(URI uri) {
        try {
            // Thử gửi HEAD request trước (nhẹ và nhanh)
            HttpRequest headRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<Void> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            // Nếu server không hỗ trợ HEAD (405 Method Not Allowed), thử gửi GET
            if (status == 405) {
                HttpRequest getRequest = HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                        .timeout(Duration.ofSeconds(3))
                        .build();
                response = httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
            }

            // Chấp nhận tất cả status code hợp lệ từ web server (kể cả 401/403 do chặn bot nhưng domain vẫn sống)
            if (status >= 500 && status <= 504) {
                log.warn("Target URL server error status {}: {}", status, uri);
            }
        } catch (Exception e) {
            log.warn("Reachability check failed for URL {}: {}", uri, e.getMessage());
            throw new IllegalArgumentException("Không thể kết nối đến URL đích (Máy chủ không phản hồi hoặc tên miền không hợp lệ)!");
        }
    }

    /**
     * Kiểm tra tính hợp lệ của custom short code:
     * 1. Độ dài: từ 3 đến 30 ký tự
     * 2. Ký tự: chỉ cho phép chữ và số (alphanumeric: a-z, A-Z, 0-9), không có ký tự đặc biệt
     * 3. Blacklist: không được trùng với các từ khóa hệ thống (admin, api, login, etc.)
     */
    public void validateCustomCode(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            return;
        }

        String code = customCode.trim();

        // 1. Kiểm tra độ dài
        if (code.length() < MIN_CUSTOM_CODE_LENGTH || code.length() > MAX_CUSTOM_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "Mã rút gọn tùy chỉnh phải có độ dài từ " + MIN_CUSTOM_CODE_LENGTH + " đến " + MAX_CUSTOM_CODE_LENGTH + " ký tự!"
            );
        }

        // 2. Kiểm tra ký tự cho phép (chỉ alphanumeric)
        if (!ALPHANUMERIC_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Mã rút gọn tùy chỉnh chỉ được chứa các ký tự chữ và số (a-z, A-Z, 0-9), không chứa dấu cách hoặc ký tự đặc biệt!"
            );
        }

        // 3. Kiểm tra blacklist từ khóa hệ thống
        if (RESERVED_KEYWORDS.contains(code.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Mã rút gọn '" + code + "' là từ khóa hệ thống dành riêng, vui lòng chọn mã khác!"
            );
        }
    }
}
