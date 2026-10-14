package com.vvttai.smart_link_shortener.modules.analytics.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chuẩn hoá dữ liệu click trước khi ghi vào DB.
 *
 * <p>Tất cả các phương thức đều pure (không side-effect), dễ unit test.</p>
 */
@Component
public class ClickNormalizer {

    // ── Device Type ───────────────────────────────────────────────────────────

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "(?i)bot|crawl|spider|slurp|mediapartners|facebookexternalhit|twitterbot|" +
            "linkedinbot|whatsapp|telegram|applebot|bingpreview|yandex|baidu|" +
            "googlebot|bingbot|duckduckbot|semrush|ahrefs|mj12bot");

    private static final Pattern TABLET_PATTERN = Pattern.compile(
            "(?i)ipad|tablet|kindle|playbook|silk|android(?!.*mobile)");

    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "(?i)mobile|android|iphone|ipod|blackberry|windows phone|opera mini|" +
            "opera mobi|palm|symbian|nokia");

    /**
     * Phân loại thiết bị từ User-Agent string.
     *
     * @return "Bot" | "Tablet" | "Mobile" | "Desktop" | "Unknown"
     */
    public String normalizeDeviceType(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        if (BOT_PATTERN.matcher(userAgent).find())     return "Bot";
        if (TABLET_PATTERN.matcher(userAgent).find())  return "Tablet";
        if (MOBILE_PATTERN.matcher(userAgent).find())  return "Mobile";
        return "Desktop";
    }

    // ── Referrer ──────────────────────────────────────────────────────────────

    private static final Set<String> SEARCH_ENGINES = Set.of(
            "google.com", "bing.com", "yahoo.com", "duckduckgo.com",
            "baidu.com", "yandex.com", "ecosia.org", "ask.com");

    /**
     * Rút domain từ referrer URL và nhóm các search engine thành nhãn thống nhất.
     *
     * <p>Ví dụ:</p>
     * <ul>
     *   <li>null / blank → "Direct"</li>
     *   <li>"https://www.google.com/search?q=..." → "Search Engine"</li>
     *   <li>"https://facebook.com/..." → "facebook.com"</li>
     * </ul>
     */
    public String normalizeReferrer(String referrer) {
        if (referrer == null || referrer.isBlank()) return "Direct";
        try {
            String host = URI.create(referrer).getHost();
            if (host == null || host.isBlank()) return "Direct";

            // Bỏ "www." prefix
            String domain = host.toLowerCase(Locale.ROOT);
            if (domain.startsWith("www.")) domain = domain.substring(4);

            // Gộp tất cả search engine thành 1 nhóm
            for (String se : SEARCH_ENGINES) {
                if (domain.equals(se) || domain.endsWith("." + se)) return "Search Engine";
            }

            return domain;
        } catch (IllegalArgumentException e) {
            return "Direct";
        }
    }

    // ── Country ───────────────────────────────────────────────────────────────

    /**
     * Chuẩn hoá country code: UPPERCASE, trim, fallback "Unknown".
     *
     * @return ISO 3166-1 alpha-2 code (2 ký tự viết hoa) hoặc "Unknown"
     */
    public String normalizeCountry(String country) {
        if (country == null || country.isBlank()) return "Unknown";
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        // Chỉ chấp nhận đúng 2 ký tự chữ cái (ISO 3166-1 alpha-2)
        if (normalized.matches("[A-Z]{2}")) return normalized;
        return "Unknown";
    }

    // ── Daily label ───────────────────────────────────────────────────────────

    /**
     * Trả về chuỗi ngày "yyyy-MM-dd" từ Instant (UTC).
     * Dùng làm label cho stat_type = DAILY.
     */
    public String dailyLabel(java.time.Instant instant) {
        if (instant == null) return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        return instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }
}
