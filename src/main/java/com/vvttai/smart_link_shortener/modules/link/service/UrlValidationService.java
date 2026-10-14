package com.vvttai.smart_link_shortener.modules.link.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;

/**
 * Facade Service điều phối toàn bộ quy trình kiểm tra an toàn và hợp lệ của URL:
 * 1. SsrfGuard: Scheme/Protocol, chặn self-loop, phân giải DNS & chặn dải IP SSRF
 * 2. SafeBrowsingClient: Kiểm tra mã độc & lừa đảo (thư viện offline — không cần API key)
 * 3. ReachabilityChecker: Kiểm tra khả năng phản hồi của server đích (fail-open bằng UNVERIFIED)
 * 4. CustomCodeValidator: Kiểm tra cú pháp, độ dài và blacklist của custom short code
 *
 * Thiết kế: Mỗi bước được tách thành component riêng biệt để dễ unit test và mock.
 */
@Service
public class UrlValidationService {

    public static final int MIN_CUSTOM_CODE_LENGTH = CustomCodeValidator.MIN_CUSTOM_CODE_LENGTH;
    public static final int MAX_CUSTOM_CODE_LENGTH = CustomCodeValidator.MAX_CUSTOM_CODE_LENGTH;

    private final SsrfGuard ssrfGuard;
    private final SafeBrowsingClient safeBrowsingClient;
    private final ReachabilityChecker reachabilityChecker;
    private final CustomCodeValidator customCodeValidator;

    @Value("${app.security.check-reachability:true}")
    private boolean checkReachability;

    public UrlValidationService(
            SsrfGuard ssrfGuard,
            SafeBrowsingClient safeBrowsingClient,
            ReachabilityChecker reachabilityChecker,
            CustomCodeValidator customCodeValidator) {
        this.ssrfGuard = ssrfGuard;
        this.safeBrowsingClient = safeBrowsingClient;
        this.reachabilityChecker = reachabilityChecker;
        this.customCodeValidator = customCodeValidator;
    }

    /**
     * Kiểm tra toàn diện tính hợp lệ và an toàn của URL đích:
     * 1. Protocol / Scheme (Chỉ cho phép http/https, chặn javascript:, data:, file:...)
     * 2. Chặn Self-redirect loop & Chặn SSRF tới IP nội bộ (resolve DNS 1 lần duy nhất)
     * 3. Kiểm tra mã độc & lừa đảo với Google Safe Browsing API
     * 4. Kiểm tra khả năng truy cập (Reachability ping) - sử dụng IP đã resolve để chống DNS rebinding
     */
    public void validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL đích không được để trống!");
        }

        String trimmedUrl = urlString.trim();

        // 1. Kiểm tra Scheme & Protocol
        URI uri = ssrfGuard.validateSchemeAndStructure(trimmedUrl);

        // 2. Chặn Self-redirect loop, chặn IP nội bộ (SSRF), và resolve DNS một lần duy nhất
        InetAddress resolvedAddress = ssrfGuard.validateNotSelfOrPrivateIp(uri);

        // 3. Kiểm tra mã độc / lừa đảo (thư viện offline)
        safeBrowsingClient.checkUrl(trimmedUrl);

        // 4. Kiểm tra Reachability (HEAD / GET request) - dùng IP đã resolve để chống DNS rebinding
        if (checkReachability) {
            reachabilityChecker.checkReachability(uri, resolvedAddress);
        }
    }

    /**
     * Xác thực custom short code thông qua CustomCodeValidator.
     * Caller phải đảm bảo customCode không null và không blank trước khi gọi.
     * Ném IllegalArgumentException nếu vi phạm độ dài, ký tự, hoặc keyword hệ thống.
     */
    public void validateCustomCode(String customCode) {
        customCodeValidator.validate(customCode);
    }
}
