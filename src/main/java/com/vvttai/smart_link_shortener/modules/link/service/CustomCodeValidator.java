package com.vvttai.smart_link_shortener.modules.link.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Thành phần kiểm tra tính hợp lệ của custom short code:
 * 1. Không rỗng
 * 2. Độ dài: từ 3 đến 30 ký tự
 * 3. Ký tự: chỉ cho phép chữ và số (alphanumeric: a-z, A-Z, 0-9), không chứa ký tự đặc biệt
 * 4. Blacklist: không được trùng với các từ khóa hệ thống (admin, api, login, swagger, actuator...)
 */
@Component
public class CustomCodeValidator {

    public static final int MIN_CUSTOM_CODE_LENGTH = 3;
    public static final int MAX_CUSTOM_CODE_LENGTH = 30;

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

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

    /**
     * Xác thực tính hợp lệ của mã rút gọn tùy chỉnh.
     * @param customCode mã rút gọn cần kiểm tra; không được null hoặc blank.
     * @throws IllegalArgumentException nếu mã vi phạm quy tắc.
     */
    public void validate(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            throw new IllegalArgumentException("Mã rút gọn tùy chỉnh không được để trống!");
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
