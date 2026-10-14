package com.vvttai.smart_link_shortener.modules.link.service;

import com.vvttai.smart_link_shortener.modules.link.dto.LinkCacheDto;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

@Service
public class LinkCacheService {

    private static final Logger log = LoggerFactory.getLogger(LinkCacheService.class);

    private static final String CACHE_PREFIX = "link:";
    private static final long CACHE_TTL_HOURS = 1; // TTL cache thông thường = 1 giờ
    private static final long EXPIRED_CACHE_TTL_MINUTES = 5; // TTL cache link hết hạn = 5 phút để chống spam DB
    private static final long NEGATIVE_CACHE_TTL_SECONDS = 60; // TTL negative cache (mã không tồn tại) = 60 giây để chống brute-force/scan
    private static final String NOT_FOUND_SENTINEL = "__LINK_NOT_FOUND__";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public LinkCacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // Jackson 3: mac dinh date = ISO-8601 string
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
    }

    /**
     * Lấy LinkCacheDto từ Redis. 
     * Trả về:
     * - null: nếu cache miss hoặc Redis lỗi.
     * - LinkCacheDto với isNotFound() == true: nếu là negative cache (link không tồn tại).
     * - LinkCacheDto hợp lệ: nếu cache hit.
     */
    public LinkCacheDto get(String shortCode) {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
            if (json == null) {
                return null;
            }
            if (NOT_FOUND_SENTINEL.equals(json)) {
                return LinkCacheDto.notFound();
            }
            return objectMapper.readValue(json, LinkCacheDto.class);
        } catch (Exception e) {
            log.warn("Redis cache read error for shortCode={}: {}", shortCode, e.getMessage());
            return null; // Fallback: query DB bình thường nếu Redis gặp sự cố
        }
    }

    /**
     * Lưu Link entity vào Redis dưới dạng LinkCacheDto JSON (mặc định TTL = 1 giờ).
     */
    public void put(Link link) {
        put(link, CACHE_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * Lưu Link entity vào Redis với TTL tùy chỉnh.
     */
    public void put(Link link, long timeout, TimeUnit unit) {
        try {
            LinkCacheDto dto = LinkCacheDto.fromEntity(link);
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(CACHE_PREFIX + link.getShortCode(), json, timeout, unit);
            log.debug("Cache PUT: shortCode={}, ttl={} {}", link.getShortCode(), timeout, unit);
        } catch (Exception e) {
            log.warn("Redis cache write error for shortCode={}: {}", link.getShortCode(), e.getMessage());
            // Không ném Exception: cache lỗi không ảnh hưởng luồng nghiệp vụ chính
        }
    }

    /**
     * Lưu trạng thái link đã hết hạn vào Redis với TTL ngắn (5 phút) để chống spam đập DB.
     */
    public void putExpired(Link link) {
        put(link, EXPIRED_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Negative Caching: lưu trạng thái mã không tồn tại vào Redis (TTL 60s)
     * để chống việc spam/scan mã ngẫu nhiên liên tục làm nghẽn DB.
     */
    public void putNotFound(String shortCode) {
        try {
            redisTemplate.opsForValue().set(
                    CACHE_PREFIX + shortCode,
                    NOT_FOUND_SENTINEL,
                    NEGATIVE_CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
            log.debug("Cache PUT NOT_FOUND: shortCode={}, ttl={}s", shortCode, NEGATIVE_CACHE_TTL_SECONDS);
        } catch (Exception e) {
            log.warn("Redis negative cache write error for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Xóa cache entry khi link bị update / delete / đổi expiration.
     */
    public void evict(String shortCode) {
        try {
            redisTemplate.delete(CACHE_PREFIX + shortCode);
            log.debug("Cache EVICT: shortCode={}", shortCode);
        } catch (Exception e) {
            log.warn("Redis cache evict error for shortCode={}: {}", shortCode, e.getMessage());
        }
    }
}
