package com.vvttai.smart_link_shortener.modules.analytics.repository;

import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ClickAnalyticsRepository extends JpaRepository<ClickAnalytics, Long> {

    long countByLinkId(Long linkId);

    @Query("SELECT c FROM ClickAnalytics c WHERE c.link.id = :linkId ORDER BY c.clickedAt DESC")
    List<ClickAnalytics> findByLinkId(@Param("linkId") Long linkId);

    List<ClickAnalytics> findTop30ByLinkIdOrderByClickedAtDesc(Long linkId);

    Page<ClickAnalytics> findByLinkIdOrderByClickedAtDesc(Long linkId, Pageable pageable);

    @Query("SELECT c FROM ClickAnalytics c WHERE c.link.id = :linkId and c.clickedAt BETWEEN :from and :to")
    List<ClickAnalytics> findByLinkIdAndDateRange(
            @Param("linkId") Long linkId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // Thống kê số lượt click theo từng ngày (dùng để vẽ biểu đồ truy cập)
    @Query(value = """
            SELECT TO_CHAR(c.clicked_at, 'YYYY-MM-DD') AS clickDate, COUNT(*) AS clickCount
            FROM click_analytics c
            WHERE c.link_id = :linkId
            GROUP BY TO_CHAR(c.clicked_at, 'YYYY-MM-DD')
            ORDER BY clickDate ASC
            """, nativeQuery = true)
    List<DailyClickProjection> findDailyClicksByLinkId(@Param("linkId") Long linkId);

    // Projection interface để Spring Data JPA tự động map kết quả thống kê
    interface DailyClickProjection {
        String getClickDate();
        Long getClickCount();
    }
}
