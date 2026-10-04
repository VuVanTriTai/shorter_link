package com.vvttai.smart_link_shortener.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Service xác minh Google ID Token bằng cách gọi Google tokeninfo endpoint.
 * Không cần thêm dependency ngoài — chỉ dùng RestTemplate có sẵn.
 */
@Service
public class GoogleTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifierService.class);
    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    @Value("${google.oauth2.client-id:}")
    private String googleClientId;

    private final RestTemplate restTemplate;

    public GoogleTokenVerifierService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Xác minh Google ID Token và trả về thông tin user.
     * Google tokeninfo endpoint trả về:
     * - sub: Google user ID (unique, dùng làm providerId)
     * - email: địa chỉ email
     * - email_verified: "true"/"false"
     * - name: tên hiển thị
     * - picture: URL ảnh đại diện
     * - aud: client ID (phải khớp với ứng dụng của mình)
     *
     * @return GoogleUserInfo nếu hợp lệ, null nếu token sai hoặc không khớp client ID
     */
    @SuppressWarnings("unchecked")
    public GoogleUserInfo verifyIdToken(String idToken) {
        try {
            Map<String, Object> response = restTemplate.getForObject(
                    GOOGLE_TOKENINFO_URL + idToken,
                    Map.class
            );

            if (response == null) {
                log.warn("Google tokeninfo returned null response");
                return null;
            }

            // Kiểm tra audience (aud) phải khớp với Google Client ID của ứng dụng
            String aud = (String) response.get("aud");
            if (googleClientId != null && !googleClientId.isBlank() && !googleClientId.equals(aud)) {
                log.warn("Google ID token audience mismatch: expected={}, got={}", googleClientId, aud);
                return null;
            }

            // Kiểm tra email đã được xác minh
            String emailVerified = String.valueOf(response.get("email_verified"));
            if (!"true".equals(emailVerified)) {
                log.warn("Google email not verified for token");
                return null;
            }

            return new GoogleUserInfo(
                    (String) response.get("sub"),
                    (String) response.get("email"),
                    (String) response.get("name"),
                    (String) response.get("picture")
            );

        } catch (Exception e) {
            log.warn("Failed to verify Google ID token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * DTO chứa thông tin user từ Google ID Token.
     */
    public record GoogleUserInfo(
            String sub,       // Google unique user ID
            String email,     // Email đã xác minh
            String name,      // Tên hiển thị
            String picture    // URL ảnh đại diện
    ) {}
}
