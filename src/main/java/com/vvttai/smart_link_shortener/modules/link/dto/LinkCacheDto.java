package com.vvttai.smart_link_shortener.modules.link.dto;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import java.time.LocalDateTime;

/**
 * DTO nhe de cache Link vao Redis.
 * Khong chua User entity (tranh serialize LAZY proxy).
 * Chi giu cac field can thiet cho redirect + validation.
 */
public record LinkCacheDto(
        Long id,
        String originalUrl,
        String shortCode,
        Long clickCount,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String password
) {
    /**
     * Chuyen tu Link entity sang LinkCacheDto de luu vao Redis.
     */
    public static LinkCacheDto fromEntity(Link link) {
        return new LinkCacheDto(
                link.getId(),
                link.getOriginalUrl(),
                link.getShortCode(),
                link.getClickCount(),
                link.isActive(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.getPassword()
        );
    }

    /**
     * Chuyen tu LinkCacheDto sang Link entity (khong kem User).
     */
    public Link toEntity() {
        Link link = new Link();
        link.setId(this.id);
        link.setOriginalUrl(this.originalUrl);
        link.setShortCode(this.shortCode);
        link.setClickCount(this.clickCount);
        link.setActive(this.active);
        link.setCreatedAt(this.createdAt);
        link.setExpiresAt(this.expiresAt);
        link.setPassword(this.password);
        return link;
    }
}