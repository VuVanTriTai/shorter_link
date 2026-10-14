package com.vvttai.smart_link_shortener.modules.analytics.dto;

import java.time.Instant;

/**
 * DTO nhẹ mang thông tin click event để serialize vào Redis Stream.
 * Dùng Instant (UTC) thay LocalDateTime để tránh lỗi múi giờ khi serialize/deserialize.
 */
public record ClickEventDto(
        Long linkId,
        Instant clickedAt,
        String ipAddress,
        String userAgent,
        String referrer,
        String deviceType,
        String country
) {
}
