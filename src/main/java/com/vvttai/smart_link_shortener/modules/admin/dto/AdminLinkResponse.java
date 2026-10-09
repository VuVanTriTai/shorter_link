package com.vvttai.smart_link_shortener.modules.admin.dto;

import java.time.LocalDateTime;

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
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        // Thông tin chủ sở hữu link
        Long userId,
        String userName
) {}
