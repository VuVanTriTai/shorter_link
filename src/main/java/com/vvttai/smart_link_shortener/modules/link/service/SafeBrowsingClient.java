package com.vvttai.smart_link_shortener.modules.link.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Safe Browsing client triển khai theo mô hình Update API (offline/local database):
 *
 * <p>Luồng hoạt động:
 * <ol>
 *   <li>Startup + định kỳ: Fetch hash-prefix list từ Google và lưu vào local Set.</li>
 *   <li>Khi check URL: Tính SHA-256 của URL, lấy 4 byte prefix, tra cứu trong local Set.</li>
 *   <li>Nếu có prefix match: Gọi API fullHashes:find để xác nhận full hash (privacy-preserving).</li>
 *   <li>Chỉ khi full hash khớp mới block URL → tránh false-positive từ prefix collision.</li>
 * </ol>
 *
 * <p>Ưu điểm so với Lookup API v4 (online):
 * <ul>
 *   <li>URL không bao giờ được gửi lên Google (chỉ gửi hash prefix khi confirm).</li>
 *   <li>Mỗi URL check không gây HTTP call ra ngoài → nhanh hơn, không tranh tài nguyên network.</li>
 *   <li>Bảo mật: API key truyền qua header X-Goog-Api-Key, không lọt vào query string/log.</li>
 *   <li>Bảo mật: Payload dựng bằng ObjectMapper, không nối chuỗi JSON thủ công (chống JSON injection).</li>
 * </ul>
 */
@Component
public class SafeBrowsingClient {

    private static final Logger log = LoggerFactory.getLogger(SafeBrowsingClient.class);

    private static final String UPDATE_ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatListUpdates:fetch";
    private static final String FULL_HASH_ENDPOINT = "https://safebrowsing.googleapis.com/v4/fullHashes:find";
    private static final List<String> THREAT_TYPES = List.of(
            "MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    // Local hash-prefix database: key = threat type, value = Set of 4-byte prefix (hex string)
    private volatile Map<String, Set<String>> localHashPrefixes = new ConcurrentHashMap<>();

    // Tracking state tokens per threat list (used in incremental updates)
    private final Map<String, String> stateTokens = new ConcurrentHashMap<>();

    // Bộ đếm lỗi để giám sát (metric nội bộ)
    private final AtomicLong failureCount = new AtomicLong(0);
    private final AtomicLong blockedCount = new AtomicLong(0);

    @Value("${google.safebrowsing.api-key:}")
    private String googleSafeBrowsingApiKey;

    public SafeBrowsingClient() {
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();

        // Executor riêng biệt với daemon threads — không tranh tài nguyên ForkJoinPool commonPool
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "safebrowsing-" + count.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Khởi tạo local database khi ứng dụng khởi động.
     */
    @PostConstruct
    public void init() {
        if (isApiKeyConfigured()) {
            try {
                refreshLocalDatabase();
            } catch (Exception e) {
                log.warn("Failed to initialize Safe Browsing local database on startup: {}. Will retry on next schedule.", e.getMessage());
            }
        } else {
            log.info("Google Safe Browsing API key not configured — local database check disabled.");
        }
    }

    /**
     * Cập nhật định kỳ local hash-prefix database (mỗi 30 phút).
     * Google khuyến nghị cập nhật 30 phút/lần với Update API.
     */
    @Scheduled(fixedDelayString = "${google.safebrowsing.refresh-interval-ms:1800000}")
    public void scheduledRefresh() {
        if (isApiKeyConfigured()) {
            try {
                refreshLocalDatabase();
            } catch (Exception e) {
                failureCount.incrementAndGet();
                log.warn("Scheduled Safe Browsing DB refresh failed (total failures={}): {}", failureCount.get(), e.getMessage());
            }
        }
    }

    /**
     * Kiểm tra URL an toàn hay không bằng local hash-prefix lookup.
     * Không gửi URL lên Google (privacy-preserving).
     * Ném {@link IllegalArgumentException} nếu URL được xác nhận nguy hiểm.
     */
    public void checkUrl(String urlString) {
        if (!isApiKeyConfigured()) {
            log.debug("Safe Browsing check skipped (API key not configured)");
            return;
        }

        if (localHashPrefixes.isEmpty()) {
            log.debug("Safe Browsing local DB not loaded yet — skipping check (fail-open)");
            return;
        }

        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(urlString.getBytes(StandardCharsets.UTF_8));
            // Lấy 4 byte đầu (prefix 32-bit) theo spec Google Safe Browsing
            String prefix = bytesToHex(Arrays.copyOf(sha256, 4));

            // Kiểm tra prefix có trong local DB không
            boolean prefixMatched = localHashPrefixes.values().stream()
                    .anyMatch(prefixSet -> prefixSet.contains(prefix));

            if (!prefixMatched) {
                return; // Sạch — không cần call mạng
            }

            // Prefix match → gọi API xác nhận full hash (chỉ gửi hash prefix, không gửi URL)
            log.debug("Safe Browsing prefix match for URL (confirming full hash)...");
            String detectedThreat = confirmFullHash(sha256);
            if (detectedThreat != null) {
                blockedCount.incrementAndGet();
                log.warn("Blocked dangerous URL via Safe Browsing offline check: threat={}, blocked_total={}",
                        detectedThreat, blockedCount.get());
                throw new IllegalArgumentException(
                        "URL bị phát hiện nguy hiểm (" + detectedThreat + " theo Google Safe Browsing)!");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("Safe Browsing check error: {}. Fail-open (total failures={}).", e.getMessage(), failureCount.get());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void refreshLocalDatabase() throws Exception {
        // Dựng request bằng ObjectMapper — chống JSON injection
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("client")
                .put("clientId", "smart-link-shortener")
                .put("clientVersion", "1.0.0");

        ArrayNode threatLists = root.putArray("listUpdateRequests");
        for (String threatType : THREAT_TYPES) {
            ObjectNode entry = threatLists.addObject();
            entry.put("threatType", threatType);
            entry.put("platformType", "ANY_PLATFORM");
            entry.put("threatEntryType", "URL");

            ObjectNode constraints = entry.putObject("constraints");
            constraints.put("supportedCompressions", "RICE");

            // Gửi state token để chỉ lấy incremental diff
            String state = stateTokens.get(threatType);
            if (state != null) {
                entry.put("state", state);
            }
        }

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(UPDATE_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", googleSafeBrowsingApiKey.trim()) // Header thay vì query string
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            failureCount.incrementAndGet();
            log.warn("Safe Browsing Update API returned {}: {}", response.statusCode(), response.body());
            return;
        }

        Map<String, Set<String>> newPrefixes = new ConcurrentHashMap<>();
        JsonNode resp = objectMapper.readTree(response.body());
        JsonNode updates = resp.get("listUpdateResponses");
        if (updates != null && updates.isArray()) {
            for (JsonNode update : updates) {
                String threatType = update.path("threatType").asText();
                String newState = update.path("newClientState").asText(null);
                if (newState != null) stateTokens.put(threatType, newState);

                Set<String> prefixes = newPrefixes.computeIfAbsent(threatType, k -> ConcurrentHashMap.newKeySet());

                // Xử lý additions (raw uncompressed hashes)
                JsonNode additions = update.path("additions");
                if (additions.isArray()) {
                    for (JsonNode addition : additions) {
                        JsonNode rawHashes = addition.path("rawHashes");
                        if (!rawHashes.isMissingNode()) {
                            int prefixSize = rawHashes.path("prefixSize").asInt(4);
                            String rawB64 = rawHashes.path("rawHashes").asText("");
                            byte[] raw = Base64.getDecoder().decode(rawB64);
                            for (int i = 0; i + prefixSize <= raw.length; i += prefixSize) {
                                prefixes.add(bytesToHex(Arrays.copyOfRange(raw, i, i + prefixSize)));
                            }
                        }
                    }
                }
            }
        }

        if (!newPrefixes.isEmpty()) {
            localHashPrefixes = newPrefixes;
            int total = newPrefixes.values().stream().mapToInt(Set::size).sum();
            log.info("Safe Browsing local DB refreshed: {} threat types, {} hash prefixes loaded.", newPrefixes.size(), total);
        }
    }

    /**
     * Gọi fullHashes:find để xác nhận full hash match.
     * Chỉ gửi hash PREFIX (4 byte), không gửi URL lên Google.
     *
     * @return threatType nếu xác nhận nguy hiểm, null nếu false-positive.
     */
    private String confirmFullHash(byte[] sha256) throws Exception {
        String prefix = bytesToHex(Arrays.copyOf(sha256, 4));
        String fullHashHex = bytesToHex(sha256);

        // Dựng payload bằng ObjectMapper
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("client")
                .put("clientId", "smart-link-shortener")
                .put("clientVersion", "1.0.0");

        ObjectNode threatInfo = root.putObject("threatInfo");
        ArrayNode threatTypes = threatInfo.putArray("threatTypes");
        THREAT_TYPES.forEach(threatTypes::add);
        threatInfo.putArray("platformTypes").add("ANY_PLATFORM");
        threatInfo.putArray("threatEntryTypes").add("URL");
        // Gửi prefix (4 byte), không phải full hash và không phải URL
        threatInfo.putArray("threatEntries").addObject().put("hash", prefix);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FULL_HASH_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", googleSafeBrowsingApiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("fullHashes:find returned {}: {}", response.statusCode(), response.body());
            return null; // Fail-open
        }

        JsonNode resp = objectMapper.readTree(response.body());
        JsonNode matches = resp.get("matches");
        if (matches != null && matches.isArray()) {
            for (JsonNode match : matches) {
                String returnedHash = bytesToHex(Base64.getDecoder().decode(match.path("threat").path("hash").asText("")));
                if (fullHashHex.equalsIgnoreCase(returnedHash)) {
                    return match.path("threatType").asText("MALWARE");
                }
            }
        }
        return null; // Prefix collision — false positive, URL thực ra an toàn
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean isApiKeyConfigured() {
        return googleSafeBrowsingApiKey != null && !googleSafeBrowsingApiKey.isBlank();
    }

    public long getFailureCount() { return failureCount.get(); }
    public long getBlockedCount() { return blockedCount.get(); }

    @PreDestroy
    public void destroy() {
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
