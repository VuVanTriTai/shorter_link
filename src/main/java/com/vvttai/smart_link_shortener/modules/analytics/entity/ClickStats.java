package com.vvttai.smart_link_shortener.modules.analytics.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Bảng tổng hợp thống kê click đã được tính trước.
 * Thay thế GROUP BY queries nặng mỗi lần load dashboard.
 *
 * <p>Các loại stat_type:</p>
 * <ul>
 *   <li>DEVICE   — "Mobile", "Desktop", "Tablet", "Bot", "Unknown"</li>
 *   <li>COUNTRY  — ISO 3166-1 alpha-2 ("VN", "US", …) hoặc "Unknown"</li>
 *   <li>REFERRER — domain nguồn ("google.com", "Direct", …)</li>
 *   <li>DAILY    — ngày định dạng "yyyy-MM-dd"</li>
 * </ul>
 *
 * <p>UPSERT: INSERT ... ON CONFLICT (link_id, stat_type, label) DO UPDATE SET total = total + 1</p>
 */
@Entity
@Table(
    name = "click_stats",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_click_stats",
        columnNames = {"link_id", "stat_type", "label"}
    )
)
public class ClickStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "stat_type", nullable = false, length = 20)
    private String statType;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "total", nullable = false)
    private Long total = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClickStats() {
    }

    public ClickStats(Long linkId, String statType, String label) {
        this.linkId = linkId;
        this.statType = statType;
        this.label = label;
        this.total = 0L;
        this.updatedAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────

    public Long getId()          { return id; }
    public Long getLinkId()      { return linkId; }
    public String getStatType()  { return statType; }
    public String getLabel()     { return label; }
    public Long getTotal()       { return total; }
    public Instant getUpdatedAt(){ return updatedAt; }

    // ── Setters (chỉ dùng bởi JPA và UPSERT logic) ──────────

    public void setTotal(Long total)           { this.total = total; }
    public void setUpdatedAt(Instant updatedAt){ this.updatedAt = updatedAt; }
}
