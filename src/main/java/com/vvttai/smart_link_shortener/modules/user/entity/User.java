package com.vvttai.smart_link_shortener.modules.user.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username")
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "role")
    private String roles = "user";

    @Column(name = "email")
    private String email;

    /**
     * Phương thức xác thực: LOCAL (đăng ký thường) hoặc GOOGLE (OAuth 2.0).
     */
    @Column(name = "auth_provider", nullable = false)
    private String authProvider = "LOCAL";

    /**
     * ID từ nhà cung cấp OAuth (Google 'sub' claim). Null nếu đăng ký thường.
     */
    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    /**
     * Tài khoản bị khoá bởi Admin (không thể đăng nhập).
     */
    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    /**
     * Soft-delete: đánh dấu tài khoản đã bị xoá nhưng vẫn giữ data link cũ.
     */
    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public User() {}

    public User(String username, String password) {
        this.username = username;
        this.password = password;
        this.authProvider = "LOCAL";
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getRoles() {
        return roles;
    }

    public String getEmail() {
        return email;
    }

    public String getAuthProvider() {
        return authProvider;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
