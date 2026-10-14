package com.vvttai.smart_link_shortener.modules.admin.dto;

import java.time.Instant;

/**
 * DTO trả về thông tin link cho Admin (kèm username chủ sở hữu).
 */
public record AdminLinkResponse(
        Long id,
        String originalUrl,
        String shortCode,
        String fullShortUrl,
        Long clickCount,
        boolean active,
        boolean banned,
        boolean hasPassword,
        Instant createdAt,
        Instant expiresAt,
        // Thông tin chủ sở hữu link
        Long userId,
        String userName
) {}
