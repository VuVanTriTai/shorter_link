package com.vvttai.smart_link_shortener.modules.link.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDateTime;

public record CreateLinkRequest(
        @NotBlank(message = "Original URL cannot be empty") @URL(message = "Invalid URL format") String originalUrl,
        String customCode,
        LocalDateTime expiresAt,
        String password) {
}
