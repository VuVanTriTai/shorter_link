package com.vvttai.smart_link_shortener.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final int ANALYTICS_THRESHOLD = 10;
    private static final int BLOCK_THRESHOLD = 30;
    private static final int WINDOW_SECONDS = 10;

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimiterService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Tăng biến đếm trong Redis và kiểm tra tầng Rate Limit tương ứng:
     * - Count <= 10: ALLOWED (Redirect + ghi Analytics)
     * - 11 <= Count <= 30: ALLOWED_WITHOUT_ANALYTICS (Redirect nhưng không ghi Analytics - chống spam click)
     * - Count > 30: BLOCKED (HTTP 429 Too Many Requests)
     */
    public RateLimitAction checkAndIncrement(String ipAddress, String shortCode) {
        String key = RATE_LIMIT_PREFIX + ipAddress + ":" + shortCode;

        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);

            // Khi key mới được khởi tạo (count == 1), thiết lập TTL
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            if (count == null) {
                return RateLimitAction.ALLOWED;
            }

            if (count <= ANALYTICS_THRESHOLD) {
                return RateLimitAction.ALLOWED;
            } else if (count <= BLOCK_THRESHOLD) {
                return RateLimitAction.ALLOWED_WITHOUT_ANALYTICS;
            } else {
                return RateLimitAction.BLOCKED;
            }
        } catch (Exception e) {
            log.warn("Redis rate limiter error for key={}: {}. Fallback to ALLOWED.", key, e.getMessage());
            // Fallback fail-open: Khi Redis gặp sự cố, cho phép request đi qua bình thường
            return RateLimitAction.ALLOWED;
        }
    }

    public static String normalizeIp(String rawIp) {
        if (rawIp == null) {
            return "unknown";
        }
        if (rawIp.equals("0:0:0:0:0:0:0:1") || rawIp.equals("::1")) {
            return "127.0.0.1";
        }
        if (rawIp.startsWith("::ffff:")) {
            return rawIp.substring(7);
        }
        return rawIp;
    }
}