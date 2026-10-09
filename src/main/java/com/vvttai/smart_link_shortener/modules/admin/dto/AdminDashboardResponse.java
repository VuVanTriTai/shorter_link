package com.vvttai.smart_link_shortener.modules.admin.dto;

import java.util.List;

/**
 * DTO trả về dashboard thống kê tổng quan cho Admin.
 */
public record AdminDashboardResponse(
        // Tổng số liệu
        long totalUsers,
        long totalLinks,
        long totalClicks,

        // User mới đăng ký
        long newUsersToday,
        long newUsersThisWeek,

        // Top trending links (click nhiều nhất)
        List<TrendingLink> topTrendingLinks
) {
    /**
     * Link phổ biến nhất (dùng cho bảng xếp hạng).
     */
    public record TrendingLink(
            Long id,
            String shortCode,
            String originalUrl,
            Long clickCount,
            String userName
    ) {}
}
