-- ============================================================
-- V2: Bảng click_stats — lưu số liệu đã tổng hợp sẵn
-- Thay thế GROUP BY queries nặng khi load dashboard
-- ============================================================

CREATE TABLE IF NOT EXISTS click_stats (
    id          BIGSERIAL    PRIMARY KEY,
    link_id     BIGINT       NOT NULL REFERENCES links(id) ON DELETE CASCADE,
    -- Loại thống kê: DEVICE | COUNTRY | REFERRER | DAILY
    stat_type   VARCHAR(20)  NOT NULL,
    -- Nhãn tương ứng: "Mobile", "VN", "google.com", "2024-01-15", ...
    label       VARCHAR(255) NOT NULL,
    total       BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_click_stats UNIQUE (link_id, stat_type, label)
);

CREATE INDEX IF NOT EXISTS idx_click_stats_link_id ON click_stats(link_id);
CREATE INDEX IF NOT EXISTS idx_click_stats_link_type ON click_stats(link_id, stat_type);
