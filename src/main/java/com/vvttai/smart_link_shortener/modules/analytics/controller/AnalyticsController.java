package com.vvttai.smart_link_shortener.modules.analytics.controller;

import com.vvttai.smart_link_shortener.modules.analytics.service.AnalyticsService;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkStatsResponse;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.service.LinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping({"/api/links/{shortCode}/stats", "/r/{shortCode}/stats"})
    public ResponseEntity<LinkStatsResponse> getLinkStats(@PathVariable String shortCode) {
        try {
            analyticsService.flushClickBuffer();
        } catch (Exception e) {
            log.warn("Failed to flush click buffer before fetching stats: {}", e.getMessage());
        }
        Link link = linkService.getLinkForStats(shortCode);
        LinkStatsResponse response = analyticsService.getLinkStats(link);
        return ResponseEntity.ok(response);
    }

    @GetMapping({"/api/links/{shortCode}/clicks", "/r/{shortCode}/clicks"})
    public ResponseEntity<?> getLinkClicks(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            analyticsService.flushClickBuffer();
        } catch (Exception e) {
            log.warn("Failed to flush click buffer before fetching clicks: {}", e.getMessage());
        }
        Link link = linkService.getLinkForStats(shortCode);
        size = Math.min(Math.max(size, 5), 100);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(analyticsService.getPaginatedClicks(link, pageable));
    }
}
