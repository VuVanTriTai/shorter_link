package com.vvttai.smart_link_shortener.modules.link.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record LinkStatsResponse(
        String shortCode,
        String originalUrl,
        long totalClicks,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        List<DailyClickStats> dailyClicks,
        Map<String, Long> deviceStats,
        Map<String, Long> countryStats,
        Map<String, Long> referrerStats,
        List<RecentClickDto> recentClicks
) {
    // Record con biểu diễn số lượt click theo từng ngày (dùng vẽ biểu đồ)
    public record DailyClickStats(
            String date,
            long clicks
    ) {}

    // Record con biểu diễn chi tiết lượt click gần nhất (dùng cho bảng nhật ký)
    public record RecentClickDto(
            LocalDateTime clickedAt,
            String ipAddress,
            String deviceType,
            String country,
            String referrer,
            String userAgent
    ) {}
}
