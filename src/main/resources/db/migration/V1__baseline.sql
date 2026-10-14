-- ============================================================
-- V1 Baseline: Mô tả schema hiện tại (tạo trước khi Flyway được tích hợp).
-- Flyway sẽ KHÔNG chạy file này nếu baseline-on-migrate=true và DB đã tồn tại.
-- File này chỉ để ghi nhận trạng thái ban đầu.
-- ============================================================

-- Bảng users
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL    PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255),
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    provider    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ
);

-- Bảng links
CREATE TABLE IF NOT EXISTS links (
    id                   BIGSERIAL     PRIMARY KEY,
    original_url         VARCHAR(2048) NOT NULL,
    short_code           VARCHAR(50)   NOT NULL UNIQUE,
    user_id              BIGINT        NOT NULL REFERENCES users(id),
    click_count          BIGINT        NOT NULL DEFAULT 0,
    is_active            BOOLEAN       NOT NULL DEFAULT true,
    banned               BOOLEAN       NOT NULL DEFAULT false,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ,
    access_password_hash VARCHAR(255)
);

-- Bảng click_analytics (LocalDateTime cũ — sẽ migrate sang TIMESTAMPTZ ở V3)
CREATE TABLE IF NOT EXISTS click_analytics (
    id          BIGSERIAL    PRIMARY KEY,
    link_id     BIGINT       NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    clicked_at  TIMESTAMP    NOT NULL,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(512),
    referrer    VARCHAR(512),
    device_type VARCHAR(512),
    country     VARCHAR(50)
);

-- Bảng refresh_tokens (nếu có)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    token       TEXT         NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
