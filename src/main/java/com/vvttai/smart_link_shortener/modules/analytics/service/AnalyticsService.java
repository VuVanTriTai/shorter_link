package com.vvttai.smart_link_shortener.modules.analytics.service;

import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkStatsResponse;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final ClickAnalyticsRepository clickAnalyticsRepository;

    public AnalyticsService(ClickAnalyticsRepository clickAnalyticsRepository) {
        this.clickAnalyticsRepository = clickAnalyticsRepository;
    }

    @Async
    public void recordClick(Link link, String ipAddress, String userAgent, String referrer, String deviceType,
            String country) {
        ClickAnalytics analytics = new ClickAnalytics(link, ipAddress, userAgent, referrer, deviceType, country);
        clickAnalyticsRepository.save(analytics);
    }

    public long getClickCount(Long linkId) {
        return clickAnalyticsRepository.countByLinkId(linkId);
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
                link.getCreatedAt(),
                link.getExpiresAt(),
                dailyClicks,
                deviceStats,
                countryStats,
                referrerStats,
                recentClicks
        );
    }
}