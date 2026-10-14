-- ============================================================
-- V3: Nâng cấp bảng click_analytics
--   1. clicked_at: LocalDateTime (không có timezone) → TIMESTAMPTZ (UTC)
--   2. Thêm stream_msg_id: idempotency key từ Redis Stream message ID
--      → đảm bảo retry không ghi duplicate
-- ============================================================

-- 1. Chuyển cột clicked_at sang TIMESTAMPTZ
ALTER TABLE click_analytics
    ALTER COLUMN clicked_at TYPE TIMESTAMPTZ
    USING clicked_at AT TIME ZONE 'UTC';

-- 2. Thêm cột stream_msg_id (định dạng Redis Stream ID: "1678886400000-0")
--    NULL cho các bản ghi cũ (insert trực tiếp), NOT NULL cho các bản ghi từ stream
ALTER TABLE click_analytics
    ADD COLUMN IF NOT EXISTS stream_msg_id VARCHAR(40) NULL;

-- 3. Unique index cho idempotency (chỉ index các hàng có stream_msg_id)
CREATE UNIQUE INDEX IF NOT EXISTS uq_click_analytics_stream_msg_id
    ON click_analytics(stream_msg_id)
    WHERE stream_msg_id IS NOT NULL;
