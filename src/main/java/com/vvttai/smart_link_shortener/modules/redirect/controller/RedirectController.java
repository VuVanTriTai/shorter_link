package com.vvttai.smart_link_shortener.modules.redirect.controller;

import com.vvttai.smart_link_shortener.common.ratelimit.RateLimitAction;
import com.vvttai.smart_link_shortener.common.ratelimit.RateLimiterService;
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

    public RedirectController(LinkService linkService,
            AnalyticsService analyticsService,
            RateLimiterService rateLimiterService) {
        this.linkService = linkService;
        this.analyticsService = analyticsService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/r/{shortCode}")
    public ResponseEntity<?> redirect(
            @PathVariable String shortCode,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String pass,
            HttpServletRequest request) {
        // --- Trích xuất & chuẩn hoá IP ---
        String rawIp = extractClientIp(request);
        String ipAddress = RateLimiterService.normalizeIp(rawIp);

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
            boolean passwordMatched = pass != null && linkService.verifyPassword(link, pass);
            if (!passwordMatched) {
                // Chưa nhập mật khẩu hoặc sai -> Chuyển hướng sang trang nhập mật khẩu của React
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create("/protect/" + shortCode));
                headers.setCacheControl(org.springframework.http.CacheControl.noCache().noStore().mustRevalidate());
                headers.setPragma("no-cache");
                headers.setExpires(0);
                return new ResponseEntity<>(headers, HttpStatus.FOUND);
            }
        }

        // Tầng 0: ALLOWED → redirect + ghi analytics
        if (action == RateLimitAction.ALLOWED) {
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            if (referrer == null)
                referrer = request.getHeader("Referrer");
            String deviceType = (userAgent != null && userAgent.contains("Mobile")) ? "Mobile" : "Desktop";
            String country = request.getLocale().getCountry();

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

    @org.springframework.web.bind.annotation.PostMapping("/r/{shortCode}/unlock")
    public ResponseEntity<?> unlockLink(
            @PathVariable String shortCode,
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String rawIp = extractClientIp(request);
        String ipAddress = RateLimiterService.normalizeIp(rawIp);

        // Chống brute-force mật khẩu bằng Rate Limiter
        RateLimitAction action = rateLimiterService.checkAndIncrement(ipAddress, "unlock:" + shortCode);
        if (action == RateLimitAction.BLOCKED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "Too Many Requests",
                    "message", "Bạn đã thử sai quá nhiều lần. Vui lòng chờ 10 giây rồi thử lại.",
                    "retryAfterSeconds", 10
            ));
        }

        Link link = linkService.getLinkByShortCode(shortCode);
        String password = body != null ? body.getOrDefault("password", "") : "";

        if (!linkService.verifyPassword(link, password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Mật khẩu không chính xác. Vui lòng kiểm tra lại!"
            ));
        }

        // Mật khẩu đúng -> Ghi nhận lượt click analytics
        if (action == RateLimitAction.ALLOWED) {
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            if (referrer == null)
                referrer = request.getHeader("Referrer");
            String deviceType = (userAgent != null && userAgent.contains("Mobile")) ? "Mobile" : "Desktop";
            String country = request.getLocale().getCountry();
            analyticsService.recordClick(link, ipAddress, userAgent, referrer, deviceType, country);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "originalUrl", link.getOriginalUrl()
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim(); // trường hợp qua proxy/load balancer
        }
        return request.getRemoteAddr();
    }
}
