package com.vvttai.smart_link_shortener.modules.analytics.entity;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Ghi nhận từng lượt click thô.
 * - clickedAt: Instant (UTC) thay thế LocalDateTime để tránh lỗi timezone.
 * - streamMsgId: idempotency key lấy từ Redis Stream message ID,
 *   đảm bảo XACK retry không tạo bản ghi duplicate.
 */
@Entity
@Table(name = "click_analytics")
public class ClickAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "link_id", nullable = false)
    private Link link;

    @Column(nullable = false)
    private Instant clickedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "referrer", length = 512)
    private String referrer;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "country", length = 10)
    private String country;

    /**
     * Redis Stream message ID (ví dụ: "1718000000000-0").
     * NULL cho các bản ghi cũ. UNIQUE (partial index trong DB).
     */
    @Column(name = "stream_msg_id", length = 40, unique = false)
    private String streamMsgId;

    protected ClickAnalytics() {
    }

    /** Constructor dùng cho fallback ghi trực tiếp (không qua stream). */
    public ClickAnalytics(Link link, String ipAddress, String userAgent,
                          String referrer, String deviceType, String country) {
        this.link = link;
        this.clickedAt = Instant.now();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referrer = referrer;
        this.deviceType = deviceType;
        this.country = country;
        this.streamMsgId = null;
    }

    /** Constructor đầy đủ dùng bởi AnalyticsBatchProcessor (từ Redis Stream). */
    public ClickAnalytics(Link link, Instant clickedAt, String ipAddress,
                          String userAgent, String referrer, String deviceType,
                          String country, String streamMsgId) {
        this.link = link;
        this.clickedAt = clickedAt != null ? clickedAt : Instant.now();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referrer = referrer;
        this.deviceType = deviceType;
        this.country = country;
        this.streamMsgId = streamMsgId;
    }

    // ── Getters ──────────────────────────────────────────────

    public Long getId()              { return id; }
    public Link getLink()            { return link; }
    public Instant getClickedAt()    { return clickedAt; }
    public String getIpAddress()     { return ipAddress; }
    public String getUserAgent()     { return userAgent; }
    public String getReferrer()      { return referrer; }
    public String getDeviceType()    { return deviceType; }
    public String getCountry()       { return country; }
    public String getStreamMsgId()   { return streamMsgId; }
}
