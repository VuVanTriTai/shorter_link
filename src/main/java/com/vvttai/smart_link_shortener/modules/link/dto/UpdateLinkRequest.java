package com.vvttai.smart_link_shortener.modules.link.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.Instant;

public record UpdateLinkRequest(
        @Size(max = 50, message = "Custom code cannot exceed 50 characters")
        String customCode,

        @NotBlank(message = "Original URL cannot be empty")
        @Size(max = 2048, message = "Original URL cannot exceed 2048 characters")
        @URL(message = "Invalid URL format")
        @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
        String originalUrl,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss][XXX]", timezone = "UTC")
        Instant expiresAt,

        Boolean active,

        @Size(max = 72, message = "Password cannot exceed 72 characters")
        String password,

        Boolean removePassword,
        Boolean removeExpiresAt) {

    public UpdateLinkRequest(
            String customCode,
            String originalUrl,
            Instant expiresAt,
            Boolean active,
            String password,
            Boolean removePassword) {
        this(customCode, originalUrl, expiresAt, active, password, removePassword, null);
    }
}
