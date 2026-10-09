package com.vvttai.smart_link_shortener.common.util;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Tạo và xác thực HMAC-based unlock token cho link được bảo vệ mật khẩu.
 *
 * Luồng:
 * 1. User POST /r/{code}/unlock với password đúng
 * 2. Server trả về token = HMAC(shortCode + "|" + expiryTimestamp, secret)
 * 3. Frontend redirect tới /r/{code}?t=base64(shortCode|expiry|hmac)
 * 4. Server xác thực token: kiểm tra HMAC + chưa hết hạn + đúng shortCode
 *
 * Token có TTL ngắn (mặc định 60 giây), gắn chặt với shortCode cụ thể.
 * Không cần lưu trạng thái (stateless) vì HMAC tự xác minh tính toàn vẹn.
 */
@Component
public class UnlockTokenService {

    private static final Logger log = LoggerFactory.getLogger(UnlockTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${app.security.unlock-token-secret:}")
    private String unlockSecret;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${app.security.unlock-token-ttl-seconds:60}")
    private int ttlSeconds;

    private byte[] secretBytes;

    @PostConstruct
    public void init() {
        // Dùng unlock-token-secret riêng nếu được cấu hình, nếu không thì derive từ JWT_SECRET
        String effectiveSecret = (unlockSecret != null && !unlockSecret.isBlank())
                ? unlockSecret
                : jwtSecret;
        if (effectiveSecret == null || effectiveSecret.isBlank()) {
            throw new IllegalStateException(
                    "❌ Cần cấu hình UNLOCK_TOKEN_SECRET hoặc JWT_SECRET để tạo unlock token!");
        }
        // Derive key riêng cho unlock (khác JWT signing key)
        this.secretBytes = ("unlock:" + effectiveSecret.trim()).getBytes(StandardCharsets.UTF_8);
        log.info("✅ UnlockTokenService khởi tạo thành công (TTL={}s)", ttlSeconds);
    }

    /**
     * Tạo unlock token cho shortCode cụ thể.
     * Format: base64url(shortCode|expiryEpochSeconds|hmacHex)
     */
    public String generateToken(String shortCode) {
        long expiry = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = shortCode + "|" + expiry;
        String hmac = computeHmac(payload);
        String raw = payload + "|" + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Xác thực unlock token.
     * Kiểm tra: HMAC hợp lệ + chưa hết hạn + đúng shortCode.
     *
     * @return true nếu token hợp lệ
     */
    public boolean validateToken(String token, String expectedShortCode) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3) return false;

            String shortCode = parts[0];
            long expiry = Long.parseLong(parts[1]);
            String providedHmac = parts[2];

            // Kiểm tra shortCode khớp
            if (!shortCode.equals(expectedShortCode)) return false;

            // Kiểm tra hết hạn
            if (System.currentTimeMillis() / 1000 > expiry) return false;

            // Kiểm tra HMAC (constant-time comparison)
            String expectedHmac = computeHmac(shortCode + "|" + expiry);
            return constantTimeEquals(providedHmac, expectedHmac);
        } catch (Exception e) {
            log.debug("Invalid unlock token: {}", e.getMessage());
            return false;
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGO));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            // Encode as hex
            StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    /**
     * Constant-time comparison để chống timing attack.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
