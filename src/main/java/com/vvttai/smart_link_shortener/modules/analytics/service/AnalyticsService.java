package com.vvttai.smart_link_shortener.modules.analytics.service;

import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics;
import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickStats;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickStatsRepository;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkStatsResponse;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dịch vụ analytics:
 * <ul>
 *   <li>{@link #recordClick} — đẩy event vào 2 luồng song song (counter + stream)</li>
 *   <li>{@link #getLinkStats} — đọc từ bảng tổng hợp {@code click_stats}, không GROUP BY</li>
 * </ul>
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final ClickStatsRepository     clickStatsRepository;
    private final LinkRepository           linkRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisClickCounterService counterService;
    private final ClickNormalizer          normalizer;

    public AnalyticsService(
            ClickAnalyticsRepository clickAnalyticsRepository,
            ClickStatsRepository clickStatsRepository,
            LinkRepository linkRepository,
            RedisTemplate<String, String> redisTemplate,
            RedisClickCounterService counterService,
            ClickNormalizer normalizer) {
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.clickStatsRepository     = clickStatsRepository;
        this.linkRepository           = linkRepository;
        this.redisTemplate            = redisTemplate;
        this.counterService           = counterService;
        this.normalizer               = normalizer;
    }

    // =========================================================================
    // Luồng ghi nhận click
    // =========================================================================

    /**
     * Ghi nhận lượt click bất đồng bộ — không block redirect request.
     *
     * <ol>
     *   <li><b>Luồng 1:</b> {@code INCR analytics:count:{linkId}} — cập nhật bộ đếm
     *       real-time trong Redis (atomic, &lt; 0.1ms).</li>
     *   <li><b>Luồng 2:</b> {@code XADD analytics:click-stream} — đẩy event vào
     *       Redis Stream để {@link AnalyticsBatchProcessor} xử lý theo batch.</li>
     * </ol>
     *
     * <p>Nếu Redis hoàn toàn không khả dụng, fallback ghi trực tiếp vào DB.</p>
     */
    @Async
    public void recordClick(Link link, String ipAddress, String userAgent,
                            String referrer, String deviceType, String country) {
        boolean redisOk = false;
        try {
            // Luồng 1: INCR counter
            counterService.increment(link.getId());

            // Luồng 2: XADD stream
            Map<String, String> fields = Map.of(
                    "linkId",    String.valueOf(link.getId()),
                    "clickedAt", Instant.now().toString(),
                    "ip",        nvl(ipAddress),
                    "ua",        nvl(userAgent),
                    "referrer",  nvl(referrer),
                    "device",    nvl(deviceType),
                    "country",   nvl(country)
            );
            redisTemplate.opsForStream().add(
                    org.springframework.data.redis.connection.stream.MapRecord.create(
                            AnalyticsBatchProcessor.STREAM_KEY, fields));

            // Giới hạn kích thước stream tránh bộ nhớ Redis phình to
            redisTemplate.opsForStream().trim(
                    AnalyticsBatchProcessor.STREAM_KEY, 200_000L, true);

            redisOk = true;
            log.debug("Pushed click event to stream for linkId={}", link.getId());
        } catch (Exception e) {
            log.warn("Redis unavailable, falling back to direct DB write: {}", e.getMessage());
        }

        // Fallback: ghi thẳng vào DB nếu Redis không khả dụng
        if (!redisOk) {
            try {
                String normalizedDevice   = normalizer.normalizeDeviceType(userAgent);
                String normalizedReferrer = normalizer.normalizeReferrer(referrer);
                String normalizedCountry  = normalizer.normalizeCountry(country);

                ClickAnalytics analytics = new ClickAnalytics(
                        link, ipAddress, userAgent,
                        normalizedReferrer, normalizedDevice, normalizedCountry);
                clickAnalyticsRepository.save(analytics);

                // Sync click_count trực tiếp
                long total = clickAnalyticsRepository.countByLinkId(link.getId());
                linkRepository.syncClickCount(link.getId(), total);
            } catch (Exception ex) {
                log.error("Fallback direct DB save failed for linkId={}: {}", link.getId(), ex.getMessage());
            }
        }
    }

    // =========================================================================
    // Query dashboard
    // =========================================================================

    /**
     * Trả về thống kê tổng hợp của một link.
     *
     * <p>Đọc từ {@code click_stats} (đã tổng hợp sẵn) thay vì chạy GROUP BY
     * trên toàn bộ {@code click_analytics} — nhanh bất kể có bao nhiêu click.</p>
     *
     * <p>totalClicks ưu tiên Redis counter (real-time), fallback về
     * {@code links.click_count} (đã sync).</p>
     */
    public LinkStatsResponse getLinkStats(Link link) {
        // 1. Total clicks: Redis counter → fallback DB
        Long redisCount = counterService.get(link.getId());
        long totalClicks = (redisCount != null)
                ? redisCount
                : link.getClickCount() != null ? link.getClickCount() : 0L;

        // 2. Đọc toàn bộ click_stats của link (1 query)
        List<ClickStats> statsList = clickStatsRepository.findByLinkId(link.getId());

        Map<String, Long> deviceStats   = filterStats(statsList, "DEVICE");
        Map<String, Long> countryStats  = filterStats(statsList, "COUNTRY");
        Map<String, Long> referrerStats = filterStats(statsList, "REFERRER");

        // Daily clicks: lấy từ DAILY stats, sắp xếp theo ngày
        List<LinkStatsResponse.DailyClickStats> dailyClicks = statsList.stream()
                .filter(s -> "DAILY".equals(s.getStatType()))
                .sorted(Comparator.comparing(ClickStats::getLabel))
                .map(s -> new LinkStatsResponse.DailyClickStats(s.getLabel(), s.getTotal()))
                .toList();

        // 3. Recent clicks: top 30 từ click_analytics (raw data, không tốn nhiều)
        List<ClickAnalytics> recentList = clickAnalyticsRepository
                .findTop30ByLinkIdOrderByClickedAtDesc(link.getId());

        List<LinkStatsResponse.RecentClickDto> recentClicks = recentList.stream()
                .map(c -> new LinkStatsResponse.RecentClickDto(
                        c.getClickedAt(),
                        maskIpAddress(c.getIpAddress()),
                        c.getDeviceType()  != null && !c.getDeviceType().isBlank()  ? c.getDeviceType()  : "Unknown",
                        c.getCountry()     != null && !c.getCountry().isBlank()     ? c.getCountry()     : "Unknown",
                        c.getReferrer()    != null && !c.getReferrer().isBlank()    ? c.getReferrer()    : "Direct",
                        c.getUserAgent()))
                .toList();

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

    public Page<LinkStatsResponse.RecentClickDto> getPaginatedClicks(Link link, Pageable pageable) {
        return clickAnalyticsRepository.findByLinkIdOrderByClickedAtDesc(link.getId(), pageable)
                .map(c -> new LinkStatsResponse.RecentClickDto(
                        c.getClickedAt(),
                        maskIpAddress(c.getIpAddress()),
                        c.getDeviceType()  != null && !c.getDeviceType().isBlank()  ? c.getDeviceType()  : "Unknown",
                        c.getCountry()     != null && !c.getCountry().isBlank()     ? c.getCountry()     : "Unknown",
                        c.getReferrer()    != null && !c.getReferrer().isBlank()    ? c.getReferrer()    : "Direct",
                        c.getUserAgent()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Long> filterStats(List<ClickStats> all, String statType) {
        return all.stream()
                .filter(s -> statType.equals(s.getStatType()))
                .collect(Collectors.toMap(ClickStats::getLabel, ClickStats::getTotal));
    }

    /**
     * Che octet/segment cuối của IP để bảo vệ quyền riêng tư khách truy cập.
     * IPv4: 192.168.1.5 → 192.168.1.***
     * IPv6: 2001:db8::1 → 2001:db8::****
     */
    private String maskIpAddress(String ip) {
        if (ip == null || ip.isBlank()) return ip;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) return ip.substring(0, lastDot) + ".***";
        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0) return ip.substring(0, lastColon) + ":****";
        return "***";
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}