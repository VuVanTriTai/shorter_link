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
    private static final long CACHE_TTL_HOURS = 1; // TTL cache = 1 gio

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
     * Lấy LinkCacheDto từ Redis. Trả về null nếu miss hoặc lỗi deserialize.
     */
    public LinkCacheDto get(String shortCode) {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, LinkCacheDto.class);
        } catch (Exception e) {
            log.warn("Redis cache read error for shortCode={}: {}", shortCode, e.getMessage());
            return null; // Fallback: query DB bình thường nếu Redis gặp sự cố
        }
    }

    /**
     * Lưu Link entity vào Redis dưới dạng LinkCacheDto JSON (TTL = 1 giờ).
     */
    public void put(Link link) {
        try {
            LinkCacheDto dto = LinkCacheDto.fromEntity(link);
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(CACHE_PREFIX + link.getShortCode(), json, CACHE_TTL_HOURS, TimeUnit.HOURS);
            log.debug("Cache PUT: shortCode={}", link.getShortCode());
        } catch (Exception e) {
            log.warn("Redis cache write error for shortCode={}: {}", link.getShortCode(), e.getMessage());
            // Không ném Exception: cache lỗi không ảnh hưởng luồng nghiệp vụ chính
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
