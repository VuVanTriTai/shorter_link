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
    public ResponseEntity<?> redirect(@PathVariable String shortCode, HttpServletRequest request) {
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

        // --- Lấy thông tin Link từ DB ---
        Link link = linkService.getLinkByShortCode(shortCode);

        // Tầng 0: ALLOWED → redirect + ghi analytics --- Ghi nhận Analytics (chỉ khi
        // ALLOWED, không ghi khi ALLOWED_WITHOUT_ANALYTICS) ---
        if (action == RateLimitAction.ALLOWED) {
            String userAgent = request.getHeader("User-Agent");
            String referrer = request.getHeader("Referer");
            if (referrer == null)
                referrer = request.getHeader("Referrer");
            String deviceType = (userAgent != null && userAgent.contains("Mobile")) ? "Mobile" : "Desktop";
            String country = request.getLocale().getCountry();

            analyticsService.recordClick(link, ipAddress, userAgent, referrer, deviceType, country);
        }
        // Tầng 2 (ALLOWED_WITHOUT_ANALYTICS): Vẫn redirect nhưng không ghi log -> chống
        // spam click tặc

        // --- Thực hiện Redirect 302 ---
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(link.getOriginalUrl()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim(); // trường hợp qua proxy/load balancer
        }
        return request.getRemoteAddr();
    }
}
