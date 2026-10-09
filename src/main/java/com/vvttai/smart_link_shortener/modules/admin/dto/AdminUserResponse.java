package com.vvttai.smart_link_shortener.modules.admin.dto;

import java.time.LocalDateTime;

/**
 * DTO trả về thông tin user cho Admin (không bao gồm password).
 */
public record AdminUserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String avatarUrl,
        String roles,
        String authProvider,
        boolean locked,
        boolean deleted,
        LocalDateTime createdAt,
        long linkCount
) {}
