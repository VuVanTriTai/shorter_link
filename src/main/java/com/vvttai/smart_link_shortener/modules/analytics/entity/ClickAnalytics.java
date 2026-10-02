package com.vvttai.smart_link_shortener.modules.analytics.entity;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import jakarta.persistence.*;
import java.time.LocalDateTime;

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
    private LocalDateTime clickedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "referrer", length = 512)
    private String referrer;

    @Column(name = "device_type", length = 512)
    private String deviceType;

    @Column(name = "country")
    private String country;

    protected ClickAnalytics() {
    }

    public ClickAnalytics(Link link, String ipAddress, String userAgent, String referrer, String deviceType,
            String country) {
        this(link, LocalDateTime.now(), ipAddress, userAgent, referrer, deviceType, country);
    }

    public ClickAnalytics(Link link, LocalDateTime clickedAt, String ipAddress, String userAgent, String referrer, String deviceType,
            String country) {
        this.link = link;
        this.clickedAt = clickedAt != null ? clickedAt : LocalDateTime.now();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referrer = referrer;
        this.deviceType = deviceType;
        this.country = country;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Link getLink() {
        return link;
    }

    public LocalDateTime getClickedAt() {
        return clickedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getCountry() {
        return country;
    }
}
