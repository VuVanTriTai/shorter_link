package com.vvttai.smart_link_shortener.common.ratelimit;

public enum RateLimitAction {
    ALLOWED,                    // Cho phép chuyển hướng và ghi nhận analytics bình thường
    ALLOWED_WITHOUT_ANALYTICS,  // Cho phép chuyển hướng nhưng không ghi nhận analytics (chống spam click)
    BLOCKED                     // Chặn truy cập với mã lỗi HTTP 429 Too Many Requests (chống DDoS)
}
