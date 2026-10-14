package com.vvttai.smart_link_shortener.modules.link.dto;

import java.time.Instant;

public record LinkResponse (
        Long id,
        String originalUrl,
        String shortCode,
        String fullShortCode, // URL rút gọn đầy đủ (ghép với app.base-url, vd: http://localhost:8080/r/abc) để copy trực tiếp
        Long clickCount,
        boolean active,
        boolean banned,
        Instant createdAt,
        Instant expiresAt,
        boolean hasPassword
){
}
