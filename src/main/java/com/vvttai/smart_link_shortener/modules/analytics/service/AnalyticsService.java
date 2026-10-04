package com.vvttai.smart_link_shortener.modules.analytics.service;

import com.vvttai.smart_link_shortener.modules.analytics.dto.ClickEventDto;
import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkStatsResponse;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final String REDIS_CLICK_BUFFER_KEY = "analytics:clicks";
    private static final int BATCH_SIZE = 500;

    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final LinkRepository linkRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsService(
            ClickAnalyticsRepository clickAnalyticsRepository,
            LinkRepository linkRepository,
            RedisTemplate<String, String> redisTemplate) {
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.linkRepository = linkRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * Ghi nhận lượt click bất đồng bộ:
     * Đẩy ClickEventDto vào Redis List buffer trong < 1ms (không block request, không nghẽn DB connection).
     */
    @Async
    public void recordClick(Link link, String ipAddress, String userAgent, String referrer, String deviceType,
            String country) {
        try {
            ClickEventDto dto = new ClickEventDto(
                    link.getId(),
                    LocalDateTime.now(),
                    ipAddress,
                    userAgent,
                    referrer,
                    deviceType,
                    country
            );
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForList().rightPush(REDIS_CLICK_BUFFER_KEY, json);
            log.debug("Buffered click event for linkId={}", link.getId());
        } catch (Exception e) {
            log.warn("Failed to push click event to Redis buffer: {}. Fallback direct DB save.", e.getMessage());
            // Fallback lưu trực tiếp vào DB nếu Redis gặp sự cố
            try {
                ClickAnalytics analytics = new ClickAnalytics(link, ipAddress, userAgent, referrer, deviceType, country);
                clickAnalyticsRepository.save(analytics);
                long total = clickAnalyticsRepository.countByLinkId(link.getId());
                linkRepository.syncClickCount(link.getId(), total);
            } catch (Exception ex) {
                log.error("Direct DB save failed: {}", ex.getMessage());
            }
        }
    }

    /**
     * Background Job chạy định kỳ mỗi 5 giây:
     * Đọc batch từ Redis List buffer -> Batch INSERT vào bảng click_analytics + Batch UPDATE click_count trên bảng links.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void flushClickBuffer() {
        Long queueSize = redisTemplate.opsForList().size(REDIS_CLICK_BUFFER_KEY);
        if (queueSize == null || queueSize == 0) {
            return;
        }

        int fetchCount = (int) Math.min(queueSize, BATCH_SIZE);
        List<String> rawEvents = new ArrayList<>(fetchCount);

        for (int i = 0; i < fetchCount; i++) {
            String item = redisTemplate.opsForList().leftPop(REDIS_CLICK_BUFFER_KEY);
            if (item == null) break;
            rawEvents.add(item);
        }

        if (rawEvents.isEmpty()) return;

        List<ClickAnalytics> analyticsList = new ArrayList<>(rawEvents.size());
        Set<Long> affectedLinkIds = new java.util.LinkedHashSet<>();

        for (String raw : rawEvents) {
            try {
                ClickEventDto dto = objectMapper.readValue(raw, ClickEventDto.class);
                Link linkRef = linkRepository.getReferenceById(dto.linkId());
                ClickAnalytics entity = new ClickAnalytics(
                        linkRef,
                        dto.clickedAt(),
                        dto.ipAddress(),
                        dto.userAgent(),
                        dto.referrer(),
                        dto.deviceType(),
                        dto.country()
                );
                analyticsList.add(entity);
                affectedLinkIds.add(dto.linkId());
            } catch (Exception e) {
                log.warn("Failed to deserialize click event: {}", e.getMessage());
            }
        }

        if (analyticsList.isEmpty()) return;

        // 1. Batch insert vào click_analytics
        try {
            clickAnalyticsRepository.saveAll(analyticsList);
            clickAnalyticsRepository.flush();
        } catch (Exception e) {
            log.error("saveAll click_analytics failed, events may be lost: {}", e.getMessage(), e);
            return; // dừng — không sync click_count nếu insert chưa thành công
        }

        // 2. Sync click_count bằng 1 SQL duy nhất (correlated subquery)
        // UPDATE links SET click_count = (SELECT COUNT(*) FROM click_analytics WHERE link_id = id)
        // WHERE id IN (:affectedLinkIds)
        // → O(1) query, self-healing: dù click_count trước đó sai bao nhiêu cũng tự sửa
        try {
            linkRepository.syncClickCountBatch(affectedLinkIds);
        } catch (Exception e) {
            log.warn("Failed to batch sync click_count: {}", e.getMessage());
        }

        log.info("Flushed {} click events. Synced click_count for {} links.",
                analyticsList.size(), affectedLinkIds.size());
    }

    public LinkStatsResponse getLinkStats(Link link) {
        long totalClicks = clickAnalyticsRepository.countByLinkId(link.getId());

        // 1. Thống kê theo ngày để vẽ biểu đồ
        List<LinkStatsResponse.DailyClickStats> dailyClicks = clickAnalyticsRepository
                .findDailyClicksByLinkId(link.getId())
                .stream()
                .map(p -> new LinkStatsResponse.DailyClickStats(p.getClickDate(), p.getClickCount()))
                .toList();

        // 2. Lấy danh sách các lượt click gần nhất (tối đa 30 lượt)
        List<ClickAnalytics> recentList = clickAnalyticsRepository
                .findTop30ByLinkIdOrderByClickedAtDesc(link.getId());

        List<LinkStatsResponse.RecentClickDto> recentClicks = recentList.stream()
                .map(c -> new LinkStatsResponse.RecentClickDto(
                        c.getClickedAt(),
                        c.getIpAddress(),
                        c.getDeviceType() != null && !c.getDeviceType().isBlank() ? c.getDeviceType() : "Desktop",
                        c.getCountry() != null && !c.getCountry().isBlank() ? c.getCountry() : "VN",
                        c.getReferrer() != null && !c.getReferrer().isBlank() ? c.getReferrer() : "Direct",
                        c.getUserAgent()
                ))
                .toList();

        // 3. Thống kê tỷ lệ thiết bị (Mobile vs Desktop)
        Map<String, Long> deviceStats = recentList.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getDeviceType() != null && !c.getDeviceType().isBlank() ? c.getDeviceType() : "Desktop",
                        Collectors.counting()
                ));

        // 4. Thống kê theo quốc gia
        Map<String, Long> countryStats = recentList.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getCountry() != null && !c.getCountry().isBlank() ? c.getCountry() : "VN",
                        Collectors.counting()
                ));

        // 5. Thống kê theo nguồn truy cập (Referrer)
        Map<String, Long> referrerStats = recentList.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getReferrer() != null && !c.getReferrer().isBlank() ? c.getReferrer() : "Direct",
                        Collectors.counting()
                ));

        return new LinkStatsResponse(
                link.getShortCode(),
                link.getOriginalUrl(),
                totalClicks,
                link.isActive(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                dailyClicks,
                deviceStats,
                countryStats,
                referrerStats,
                recentClicks
        );
    }

    public org.springframework.data.domain.Page<LinkStatsResponse.RecentClickDto> getPaginatedClicks(
            Link link,
            org.springframework.data.domain.Pageable pageable) {
        return clickAnalyticsRepository.findByLinkIdOrderByClickedAtDesc(link.getId(), pageable)
                .map(c -> new LinkStatsResponse.RecentClickDto(
                        c.getClickedAt(),
                        c.getIpAddress(),
                        c.getDeviceType() != null && !c.getDeviceType().isBlank() ? c.getDeviceType() : "Desktop",
                        c.getCountry() != null && !c.getCountry().isBlank() ? c.getCountry() : "VN",
                        c.getReferrer() != null && !c.getReferrer().isBlank() ? c.getReferrer() : "Direct",
                        c.getUserAgent()
                ));
    }
}