package com.vvttai.smart_link_shortener.modules.analytics.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Dịch vụ đếm click real-time bằng Redis INCR.
 *
 * <p>Mỗi lượt click gọi {@link #increment(Long)} — 1 lệnh Redis INCR atomic (&lt; 0.1ms).
 * Không block request, không nghẽn DB connection pool.</p>
 *
 * <p>Bộ đếm được sync về {@code links.click_count} định kỳ bởi
 * {@link AnalyticsBatchProcessor} sau khi flush batch thành công.</p>
 */
@Service
public class RedisClickCounterService {

    /** Prefix key: analytics:count:{linkId} */
    private static final String KEY_PREFIX = "analytics:count:";

    private final RedisTemplate<String, String> redisTemplate;

    public RedisClickCounterService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Tăng bộ đếm click của link lên 1.
     *
     * @param linkId ID của link
     * @return giá trị mới sau khi tăng, hoặc null nếu Redis không phản hồi
     */
    public Long increment(Long linkId) {
        try {
            return redisTemplate.opsForValue().increment(key(linkId));
        } catch (Exception e) {
            // Redis không khả dụng — bỏ qua, click_count sẽ được sync sau bởi batch processor
            return null;
        }
    }

    /**
     * Lấy giá trị bộ đếm hiện tại (đọc snapshot, không block).
     *
     * @param linkId ID của link
     * @return số click hiện tại trong Redis, hoặc null nếu key chưa tồn tại / Redis lỗi
     */
    public Long get(Long linkId) {
        try {
            String val = redisTemplate.opsForValue().get(key(linkId));
            return val != null ? Long.parseLong(val) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Đặt lại bộ đếm về giá trị cụ thể (dùng sau khi sync về DB).
     * Không gọi DELETE để tránh race condition với INCR đang chạy song song.
     *
     * @param linkId ID của link
     * @param value  giá trị mới (thường là click_count đã sync)
     */
    public void reset(Long linkId, long value) {
        try {
            redisTemplate.opsForValue().set(key(linkId), String.valueOf(value));
        } catch (Exception e) {
            // Bỏ qua — lần flush sau sẽ dùng giá trị hiện tại của Redis
        }
    }

    private String key(Long linkId) {
        return KEY_PREFIX + linkId;
    }
}
