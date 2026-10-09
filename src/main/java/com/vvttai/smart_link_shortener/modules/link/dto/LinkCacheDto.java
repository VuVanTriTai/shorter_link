package com.vvttai.smart_link_shortener.modules.link.dto;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import java.time.LocalDateTime;

/**
 * DTO nhe de cache Link vao Redis.
 * Khong chua User entity (tranh serialize LAZY proxy).
 * Chi giu cac field can thiet cho redirect + validation.
 *
 * QUAN TRONG: KHONG cache BCrypt hash password trong Redis.
 * Chi luu co hasPassword (boolean) de biet link co can mat khau khong.
 * Khi can verify password, phai doc hash tu DB truc tiep.
 */
public record LinkCacheDto(
        Long id,
        String originalUrl,
        String shortCode,
        Long clickCount,
        boolean active,
        boolean banned,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean hasPassword
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
                link.isBanned(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.hasPassword()
        );
    }

    /**
     * Chuyen tu LinkCacheDto sang Link entity (khong kem User).
     * Luu y: password KHONG duoc khoi phuc tu cache (chi co hasPassword flag).
     * Link.password se la null => khi can verify phai query DB.
     */
    public Link toEntity() {
        Link link = new Link();
        link.setId(this.id);
        link.setOriginalUrl(this.originalUrl);
        link.setShortCode(this.shortCode);
        link.setClickCount(this.clickCount);
        link.setActive(this.active);
        link.setBanned(this.banned);
        link.setCreatedAt(this.createdAt);
        link.setExpiresAt(this.expiresAt);
        // KHÔNG set password: link từ cache sẽ có password=null
        // hasPassword() trên entity sẽ trả về false -> phải dùng flag riêng
        return link;
    }
}