package com.vvttai.smart_link_shortener.modules.analytics.repository;

import com.vvttai.smart_link_shortener.modules.analytics.entity.ClickAnalytics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClickAnalyticsRepository extends JpaRepository<ClickAnalytics, Long> {

    long countByLinkId(Long linkId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM ClickAnalytics c WHERE c.link.id = :linkId")
    void deleteByLinkId(@Param("linkId") Long linkId);

    List<ClickAnalytics> findTop30ByLinkIdOrderByClickedAtDesc(Long linkId);

    Page<ClickAnalytics> findByLinkIdOrderByClickedAtDesc(Long linkId, Pageable pageable);

    /**
     * Batch INSERT với ON CONFLICT DO NOTHING dựa trên stream_msg_id (partial unique index).
     * Đảm bảo idempotency: retry message từ Redis Stream không tạo bản ghi duplicate.
     *
     * <p>Dùng UNNEST để tránh N INSERT riêng lẻ — 1 câu SQL duy nhất cho toàn batch.</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO click_analytics
                (link_id, clicked_at, ip_address, user_agent, referrer, device_type, country, stream_msg_id)
            SELECT
                unnest(:linkIds::bigint[]),
                unnest(:clickedAts::timestamptz[]),
                unnest(:ipAddresses::text[]),
                unnest(:userAgents::text[]),
                unnest(:referrers::text[]),
                unnest(:deviceTypes::text[]),
                unnest(:countries::text[]),
                unnest(:streamMsgIds::text[])
            ON CONFLICT (stream_msg_id) WHERE stream_msg_id IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int batchInsertWithIdempotency(
            @Param("linkIds")       Long[]    linkIds,
            @Param("clickedAts")    String[]  clickedAts,
            @Param("ipAddresses")   String[]  ipAddresses,
            @Param("userAgents")    String[]  userAgents,
            @Param("referrers")     String[]  referrers,
            @Param("deviceTypes")   String[]  deviceTypes,
            @Param("countries")     String[]  countries,
            @Param("streamMsgIds")  String[]  streamMsgIds
    );
}
