package com.vvttai.smart_link_shortener.modules.link.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.Instant;

public record CreateLinkRequest(
        @NotBlank(message = "Original URL cannot be empty")
        @Size(max = 2048, message = "Original URL cannot exceed 2048 characters")
        @URL(message = "Invalid URL format")
        @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
        String originalUrl,

        @Size(max = 50, message = "Custom code cannot exceed 50 characters")
        String customCode,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss][XXX]", timezone = "UTC")
        Instant expiresAt,

        @Size(max = 72, message = "Password cannot exceed 72 characters")
        String password) {
}
