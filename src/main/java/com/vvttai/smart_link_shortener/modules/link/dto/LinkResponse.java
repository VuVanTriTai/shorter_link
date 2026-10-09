package com.vvttai.smart_link_shortener.modules.link.dto;

import java.time.LocalDateTime;

public record LinkResponse (
        Long id,
        String originalUrl,
        String shortCode,
        String fullShortCode,//Url đầy đủ để coppy luôn
        Long clickCount,
        boolean active,
        boolean banned,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean hasPassword
){

}
