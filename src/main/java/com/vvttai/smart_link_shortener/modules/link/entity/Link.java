package com.vvttai.smart_link_shortener.modules.link.entity;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "links")
@EntityListeners(AuditingEntityListener.class)
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "short_code", nullable = false, unique = true, length = 50)
    private String shortCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "click_count", nullable = false)
    private Long clickCount = 0L;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "banned", nullable = false, columnDefinition = "boolean default false")
    private boolean banned = false;

    @OneToMany(mappedBy = "link", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics> clickAnalytics = new java.util.ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "access_password_hash")
    private String accessPasswordHash;

    public Link() {
        // Constructor rỗng không tự gán createdAt = now() để không làm sai lệch toEntity()
    }

    public Link(String originalUrl, String shortCode, User user) {
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.user = user;
        this.clickCount = 0L;
        this.active = true;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    /**
     * Lấy username trực tiếp qua quan hệ với User entity (tránh denormalize lệch dữ liệu).
     */
    public String getUserName() {
        return user != null ? user.getUsername() : null;
    }

    public Long getClickCount() {
        return clickCount;
    }

    public void setClickCount(Long clickCount) {
        this.clickCount = clickCount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isBanned() {
        return banned;
    }

    public void setBanned(boolean banned) {
        this.banned = banned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getPassword() {
        return accessPasswordHash;
    }

    public void setPassword(String password) {
        this.accessPasswordHash = password;
    }

    public String getAccessPasswordHash() {
        return accessPasswordHash;
    }

    public void setAccessPasswordHash(String accessPasswordHash) {
        this.accessPasswordHash = accessPasswordHash;
    }

    public boolean hasPassword() {
        return accessPasswordHash != null && !accessPasswordHash.isBlank();
    }
}