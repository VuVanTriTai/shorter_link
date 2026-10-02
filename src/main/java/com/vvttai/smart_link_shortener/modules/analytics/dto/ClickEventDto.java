package com.vvttai.smart_link_shortener.modules.analytics.dto;

import java.time.LocalDateTime;

/**
 * DTO nhe mang thong tin click event de serialize vao Redis List buffer.
 */
public record ClickEventDto(
        Long linkId,
        LocalDateTime clickedAt,
        String ipAddress,
        String userAgent,
        String referrer,
        String deviceType,
        String country
) {
}
