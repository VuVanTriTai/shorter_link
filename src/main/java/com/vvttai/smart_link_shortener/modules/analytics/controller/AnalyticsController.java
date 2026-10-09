package com.vvttai.smart_link_shortener.modules.analytics.controller;

import com.vvttai.smart_link_shortener.modules.analytics.service.AnalyticsService;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkStatsResponse;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.service.LinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final LinkService linkService;

    public AnalyticsController(AnalyticsService analyticsService, LinkService linkService) {
        this.analyticsService = analyticsService;
        this.linkService = linkService;
    }

    /**
     * Xem thống kê chi tiết của link.
     * Yêu cầu đăng nhập + chỉ chủ sở hữu link mới được xem.
     * Đã xóa alias /r/{shortCode}/stats để tránh lộ dữ liệu qua route công khai.
     */
    @GetMapping("/api/links/{shortCode}/stats")
    public ResponseEntity<LinkStatsResponse> getLinkStats(
            @PathVariable String shortCode,
            Principal principal) {
        try {
            analyticsService.flushClickBuffer();
        } catch (Exception e) {
            log.warn("Failed to flush click buffer before fetching stats: {}", e.getMessage());
        }
        // Kiểm tra chủ sở hữu: chỉ owner mới được xem stats
        Link link = linkService.getLinkForStatsOwnedBy(shortCode, principal.getName());
        LinkStatsResponse response = analyticsService.getLinkStats(link);
        return ResponseEntity.ok(response);
    }

    /**
     * Xem danh sách lượt click phân trang.
     * Yêu cầu đăng nhập + chỉ chủ sở hữu link mới được xem.
     * Đã xóa alias /r/{shortCode}/clicks để tránh lộ dữ liệu qua route công khai.
     */
    @GetMapping("/api/links/{shortCode}/clicks")
    public ResponseEntity<?> getLinkClicks(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        try {
            analyticsService.flushClickBuffer();
        } catch (Exception e) {
            log.warn("Failed to flush click buffer before fetching clicks: {}", e.getMessage());
        }
        // Kiểm tra chủ sở hữu: chỉ owner mới được xem clicks
        Link link = linkService.getLinkForStatsOwnedBy(shortCode, principal.getName());
        size = Math.min(Math.max(size, 5), 100);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(analyticsService.getPaginatedClicks(link, pageable));
    }
}
