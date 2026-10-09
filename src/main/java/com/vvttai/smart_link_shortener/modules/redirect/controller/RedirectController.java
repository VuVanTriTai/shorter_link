package com.vvttai.smart_link_shortener.modules.redirect.controller;

import com.vvttai.smart_link_shortener.common.ratelimit.RateLimitAction;
import com.vvttai.smart_link_shortener.common.ratelimit.RateLimiterService;
import com.vvttai.smart_link_shortener.common.util.UnlockTokenService;
import com.vvttai.smart_link_shortener.modules.analytics.service.AnalyticsService;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
public class RedirectController {

    private final LinkService linkService;
    private final AnalyticsService analyticsService;
    private final RateLimiterService rateLimiterService;
    private final UnlockTokenService unlockTokenService;

    public RedirectController(LinkService linkService,
            AnalyticsService analyticsService,
            RateLimiterService rateLimiterService,
            UnlockTokenService unlockTokenService) {
        this.linkService = linkService;
        this.analyticsService = analyticsService;
        this.rateLimiterService = rateLimiterService;
        this.unlockTokenService = unlockTokenService;
    }

    /**
     * Redirect endpoint.
     *
     * Luồng xác thực mật khẩu (thay vì ?pass= trên URL):
     * 1. GET /r/{code} → phát hiện link có mật khẩu → redirect sang /protect/{code}
     * 2. React form POST /r/{code}/unlock với JSON body → server verify → trả về unlock token
     * 3. Frontend redirect tới /r/{code}?t=<HMAC_TOKEN>
     * 4. GET /r/{code}?t=... → server validate token → redirect tới URL đích
     *
     * Lợi ích: mật khẩu KHÔNG bao giờ xuất hiện trên URL (tránh log, history, Referer leak).
     *
     * IP extraction: dùng request.getRemoteAddr() thay vì tự parse X-Forwarded-For.
     * Tomcat (server.forward-headers-strategy=native) đã xử lý trusted proxy headers.
     */
    @GetMapping("/r/{shortCode}")
    public ResponseEntity<?> redirect(
            @PathVariable String shortCode,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String t,
            HttpServletRequest request) {
        // --- Trích xuất & chuẩn hoá IP ---
        // Tomcat đã xử lý X-Forwarded-For từ trusted proxy → getRemoteAddr() trả về client IP thật
        String ipAddress = RateLimiterService.normalizeIp(request.getRemoteAddr());

        // --- Kiểm tra Rate Limit (Tiered) ---
        RateLimitAction action = rateLimiterService.checkAndIncrement(ipAddress, shortCode);

        if (action == RateLimitAction.BLOCKED) {
            // Tầng 3: Chặn hoàn toàn, trả về HTTP 429
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Too Many Requests",
                            "message", "Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.",
                            "retryAfterSeconds", 10));
        }

        // --- Lấy thông tin Link từ DB / Cache ---
        Link link = linkService.getLinkByShortCode(shortCode);

        // --- Kiểm tra mật khẩu bảo vệ ---
        if (link.hasPassword()) {
            // Kiểm tra unlock token (thay vì ?pass= trên URL)
            boolean tokenValid = t != null && !t.isBlank() && unlockTokenService.validateToken(t, shortCode);
            if (!tokenValid) {
                // Chưa có token hợp lệ → chuyển hướng sang trang nhập mật khẩu
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create("/protect/" + shortCode));
                headers.setCacheControl(org.springframework.http.CacheControl.noCache().noStore().mustRevalidate());
                headers.setPragma("no-cache");
                headers.setExpires(0);
                return new ResponseEntity<>(headers, HttpStatus.FOUND);
            }
            // Token hợp lệ → cho đi qua
        }

        // Tầng 0: ALLOWED → redirect + ghi analytics
        if (action == RateLimitAction.ALLOWED) {
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            if (referrer == null)
                referrer = request.getHeader("Referrer");
            String deviceType = (userAgent != null && userAgent.contains("Mobile")) ? "Mobile" : "Desktop";
            // Bug fix: getLocale().getCountry() trả về ngôn ngữ trình duyệt, KHÔNG phải quốc gia
            // Ưu tiên CF-IPCountry (Cloudflare), fallback Unknown
            String country = request.getHeader("CF-IPCountry");
            if (country == null || country.isBlank() || "XX".equals(country)) {
                country = "Unknown";
            }

            analyticsService.recordClick(link, ipAddress, userAgent, referrer, deviceType, country);
        }

        // --- Thực hiện Redirect 302 ---
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(link.getOriginalUrl()));
        headers.setCacheControl(org.springframework.http.CacheControl.noCache().noStore().mustRevalidate());
        headers.setPragma("no-cache");
        headers.setExpires(0);
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/r/{shortCode}/info")
    public ResponseEntity<?> getLinkInfo(@PathVariable String shortCode) {
        Link link = linkService.getLinkByShortCode(shortCode);
        return ResponseEntity.ok(Map.of(
                "shortCode", link.getShortCode(),
                "hasPassword", link.hasPassword()
        ));
    }

    /**
     * Unlock endpoint: nhận mật khẩu qua JSON body (POST), trả về HMAC token ngắn hạn.
     *
     * Frontend nhận token rồi redirect tới /r/{code}?t=<token> để hoàn tất.
     * Mật khẩu KHÔNG bao giờ xuất hiện trên URL.
     *
     * Rate limiting:
     * - Chỉ đếm lần SAI (lần đúng không tăng counter)
     * - 5 lần sai / phút / (IP + code)
     * - 20 lần sai / phút / code (chống distributed brute-force)
     * - Fail-closed: nếu Redis lỗi, dùng in-memory fallback
     */
    @org.springframework.web.bind.annotation.PostMapping("/r/{shortCode}/unlock")
    public ResponseEntity<?> unlockLink(
            @PathVariable String shortCode,
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            HttpServletRequest request) {
        // Dùng getRemoteAddr() — Tomcat đã xử lý trusted proxy headers
        String ipAddress = RateLimiterService.normalizeIp(request.getRemoteAddr());

        // Kiểm tra brute-force limit TRƯỚC KHI verify mật khẩu (tránh tốn CPU cho BCrypt)
        if (rateLimiterService.isUnlockBlocked(ipAddress, shortCode)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "Too Many Requests",
                    "message", "Bạn đã thử sai quá nhiều lần. Vui lòng chờ 1 phút rồi thử lại.",
                    "retryAfterSeconds", 60
            ));
        }

        // Lấy link (validate active/expired/banned)
        linkService.getLinkByShortCode(shortCode);

        String password = body != null ? body.getOrDefault("password", "") : "";

        // Verify mật khẩu (đọc hash trực tiếp từ DB, không qua cache)
        if (!linkService.verifyPassword(shortCode, password)) {
            // Chỉ đếm lần SAI (không đếm lần đúng)
            rateLimiterService.recordUnlockFailure(ipAddress, shortCode);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Mật khẩu không chính xác. Vui lòng kiểm tra lại!"
            ));
        }

        // Mật khẩu đúng → Tạo HMAC unlock token (TTL = 60 giây, gắn với shortCode)
        String unlockToken = unlockTokenService.generateToken(shortCode);

        // Ghi nhận analytics cho lượt click (chỉ khi rate limit cho phép)
        RateLimitAction action = rateLimiterService.checkAndIncrement(ipAddress, shortCode);
        if (action == RateLimitAction.ALLOWED) {
            Link link = linkService.getLinkByShortCode(shortCode);
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            if (referrer == null)
                referrer = request.getHeader("Referrer");
            String deviceType = (userAgent != null && userAgent.contains("Mobile")) ? "Mobile" : "Desktop";
            String country = request.getHeader("CF-IPCountry");
            if (country == null || country.isBlank() || "XX".equals(country)) {
                country = "Unknown";
            }
            analyticsService.recordClick(link, ipAddress, userAgent, referrer, deviceType, country);
        }

        // Trả token + redirect URL cho frontend
        return ResponseEntity.ok(Map.of(
                "success", true,
                "token", unlockToken,
                "redirectUrl", "/r/" + shortCode + "?t=" + unlockToken
        ));
    }
}
