package com.vvttai.smart_link_shortener.modules.analytics.repository;

import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClickStatsRepository extends JpaRepository<ClickStats, Long> {

    /** Lấy toàn bộ stats của 1 link (dùng bởi getLinkStats). */
    List<ClickStats> findByLinkId(Long linkId);

    /** Lấy stats theo link + loại (DEVICE, COUNTRY, REFERRER, DAILY). */
    List<ClickStats> findByLinkIdAndStatType(Long linkId, String statType);

    /**
     * UPSERT: tăng total thêm :delta cho một (link_id, stat_type, label).
     * INSERT nếu chưa có, UPDATE nếu đã tồn tại.
     * Dùng native query để tận dụng ON CONFLICT của PostgreSQL.
     */
    @Modifying
    @Query(value = """
            INSERT INTO click_stats (link_id, stat_type, label, total, updated_at)
            VALUES (:linkId, :statType, :label, :delta, now())
            ON CONFLICT ON CONSTRAINT uq_click_stats
            DO UPDATE SET
                total      = click_stats.total + :delta,
                updated_at = now()
            """, nativeQuery = true)
    void upsertStat(
            @Param("linkId")   Long linkId,
            @Param("statType") String statType,
            @Param("label")    String label,
            @Param("delta")    long delta
    );

    /** Xoá toàn bộ stats khi link bị xoá (thường đã được cascade, để dự phòng). */
    @Modifying
    @Query("DELETE FROM ClickStats s WHERE s.linkId = :linkId")
    void deleteByLinkId(@Param("linkId") Long linkId);
}
