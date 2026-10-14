package com.vvttai.smart_link_shortener.modules.link.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LinkStatsResponse(
        String shortCode,
        String originalUrl,
        long totalClicks,
        boolean active,
        Instant createdAt,
        Instant expiresAt,
        List<DailyClickStats> dailyClicks,
        Map<String, Long> deviceStats,
        Map<String, Long> countryStats,
        Map<String, Long> referrerStats,
        List<RecentClickDto> recentClicks
) {
    /** Số lượt click theo từng ngày (dùng vẽ biểu đồ truy cập). */
    public record DailyClickStats(
            String date,
            long clicks
    ) {}

    /** Chi tiết lượt click gần nhất (dùng cho bảng nhật ký). */
    public record RecentClickDto(
            Instant clickedAt,
            String ipAddress,
            String deviceType,
            String country,
            String referrer,
            String userAgent
    ) {}
}
