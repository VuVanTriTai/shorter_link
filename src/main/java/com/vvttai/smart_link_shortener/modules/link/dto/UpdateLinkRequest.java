package com.vvttai.smart_link_shortener.modules.link.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDateTime;

public record UpdateLinkRequest(
        String customCode,
        @NotBlank(message = "Original URL cannot be emtry") @URL(message = "Invalid URL format") String originalUrl,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        LocalDateTime expiresAt) {
}
