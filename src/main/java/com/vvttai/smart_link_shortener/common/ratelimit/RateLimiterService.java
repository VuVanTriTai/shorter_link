package com.vvttai.smart_link_shortener.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    // ===== Rate limit cho redirect (giữ nguyên logic cũ) =====
    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final int ANALYTICS_THRESHOLD = 10;
    private static final int BLOCK_THRESHOLD = 30;
    private static final int WINDOW_SECONDS = 10;

    // ===== Rate limit cho brute-force mật khẩu link =====
    private static final String BRUTE_FORCE_PREFIX = "bf:unlock:";
    private static final int BF_MAX_FAILURES_PER_IP_CODE = 5;    // 5 lần sai / phút / (IP + code)
    private static final int BF_MAX_FAILURES_PER_CODE = 20;      // 20 lần sai / phút / code (chống distributed brute-force)
    private static final int BF_WINDOW_SECONDS = 60;

    // Fallback in-memory khi Redis lỗi (cho brute-force protection)
    private final Map<String, InMemoryCounter> inMemoryCounters = new ConcurrentHashMap<>();

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

    /**
     * Kiểm tra brute-force limit cho /unlock endpoint.
     * FAIL-CLOSED: Khi Redis lỗi, dùng in-memory fallback thay vì cho phép.
     *
     * Chỉ gọi khi mật khẩu SAI (không đếm lần đúng).
     *
     * 2 tầng chặn:
     * 1. Per IP+code: 5 lần sai / phút → chặn IP cụ thể cho code cụ thể
     * 2. Per code (bất kể IP): 20 lần sai / phút → chặn code hoàn toàn (chống distributed brute-force)
     *
     * @return true nếu bị chặn (đã vượt quá giới hạn)
     */
    public boolean isUnlockBlocked(String ipAddress, String shortCode) {
        String ipCodeKey = BRUTE_FORCE_PREFIX + ipAddress + ":" + shortCode;
        String codeKey = BRUTE_FORCE_PREFIX + "global:" + shortCode;

        try {
            // Kiểm tra per-IP+code
            String ipCodeCount = stringRedisTemplate.opsForValue().get(ipCodeKey);
            if (ipCodeCount != null && Long.parseLong(ipCodeCount) >= BF_MAX_FAILURES_PER_IP_CODE) {
                return true;
            }

            // Kiểm tra per-code (distributed brute-force)
            String codeCount = stringRedisTemplate.opsForValue().get(codeKey);
            if (codeCount != null && Long.parseLong(codeCount) >= BF_MAX_FAILURES_PER_CODE) {
                log.warn("Distributed brute-force detected on shortCode={} ({}+ failed attempts from multiple IPs)",
                        shortCode, BF_MAX_FAILURES_PER_CODE);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.warn("Redis brute-force check error: {}. Falling back to in-memory.", e.getMessage());
            // FAIL-CLOSED: dùng in-memory fallback
            return isBlockedInMemory(ipCodeKey);
        }
    }

    /**
     * Ghi nhận 1 lần nhập sai mật khẩu.
     * Chỉ gọi SAU KHI xác nhận mật khẩu sai (không đếm lần đúng).
     */
    public void recordUnlockFailure(String ipAddress, String shortCode) {
        String ipCodeKey = BRUTE_FORCE_PREFIX + ipAddress + ":" + shortCode;
        String codeKey = BRUTE_FORCE_PREFIX + "global:" + shortCode;

        try {
            // Tăng per-IP+code counter
            Long ipCount = stringRedisTemplate.opsForValue().increment(ipCodeKey);
            if (ipCount != null && ipCount == 1L) {
                stringRedisTemplate.expire(ipCodeKey, BF_WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            // Tăng per-code counter (distributed protection)
            Long codeCount = stringRedisTemplate.opsForValue().increment(codeKey);
            if (codeCount != null && codeCount == 1L) {
                stringRedisTemplate.expire(codeKey, BF_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Redis brute-force record error: {}. Using in-memory fallback.", e.getMessage());
            recordFailureInMemory(ipCodeKey);
        }
    }

    // ===== In-memory fallback cho brute-force (khi Redis lỗi) =====

    private boolean isBlockedInMemory(String key) {
        InMemoryCounter counter = inMemoryCounters.get(key);
        if (counter == null) return false;
        if (counter.isExpired()) {
            inMemoryCounters.remove(key);
            return false;
        }
        return counter.count.get() >= BF_MAX_FAILURES_PER_IP_CODE;
    }

    private void recordFailureInMemory(String key) {
        inMemoryCounters.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                return new InMemoryCounter();
            }
            existing.count.incrementAndGet();
            return existing;
        });
        // Cleanup stale entries mỗi 100 records để tránh memory leak
        if (inMemoryCounters.size() > 1000) {
            inMemoryCounters.entrySet().removeIf(e -> e.getValue().isExpired());
        }
    }

    private static class InMemoryCounter {
        final AtomicInteger count = new AtomicInteger(1);
        final long expiresAt = System.currentTimeMillis() + (BF_WINDOW_SECONDS * 1000L);

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
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