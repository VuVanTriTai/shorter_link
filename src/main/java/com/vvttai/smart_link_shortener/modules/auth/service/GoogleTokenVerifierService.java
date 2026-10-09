package com.vvttai.smart_link_shortener.modules.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.vvttai.smart_link_shortener.common.exception.ExternalServiceException;
import com.vvttai.smart_link_shortener.common.exception.InvalidTokenException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Xác minh Google ID Token bằng thư viện google-api-client.
 * <p>
 * Ưu điểm so với gọi endpoint tokeninfo:
 * <ul>
 *   <li>Verify chữ ký RSA offline — không cần round-trip HTTP mỗi lần login.</li>
 *   <li>GoogleIdTokenVerifier tự cache public key và làm mới khi hết hạn.</li>
 *   <li>Phân biệt rõ lỗi: token sai → {@link InvalidTokenException},
 *       Google down / network lỗi → {@link ExternalServiceException}.</li>
 * </ul>
 * <p>
 * Yêu cầu bắt buộc: {@code google.oauth2.client-id} phải được cấu hình.
 * Nếu thiếu, ứng dụng sẽ <b>fail-fast</b> ngay khi khởi động.
 */
@Service
public class GoogleTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifierService.class);

    /**
     * Google Client ID của ứng dụng.
     * Bắt buộc — không có giá trị mặc định để fail-fast nếu quên cấu hình.
     */
    @Value("${google.oauth2.client-id}")
    private String googleClientId;

    private GoogleIdTokenVerifier verifier;

    /**
     * Khởi tạo {@link GoogleIdTokenVerifier} sau khi bean được inject xong.
     * Nếu {@code google.oauth2.client-id} chưa được cấu hình, Spring sẽ
     * ném {@link org.springframework.beans.factory.BeanCreationException} và
     * ứng dụng dừng khởi động ngay lập tức (fail-fast).
     */
    @PostConstruct
    void init() {
        verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance()
        )
                .setAudience(Collections.singletonList(googleClientId))
                .build();
        log.info("GoogleIdTokenVerifier initialized for client-id={}", googleClientId);
    }

    /**
     * Xác minh Google ID Token.
     * <ul>
     *   <li>Ký hợp lệ + aud khớp + chưa hết hạn → trả về {@link GoogleUserInfo}.</li>
     *   <li>Token sai / hết hạn / aud không khớp → ném {@link InvalidTokenException} (→ HTTP 401).</li>
     *   <li>Lỗi mạng hoặc Google không phản hồi → ném {@link ExternalServiceException} (→ HTTP 503).</li>
     * </ul>
     *
     * @param idToken chuỗi Google ID Token từ client (KHÔNG đưa vào query string / log)
     * @return thông tin user đã xác minh
     * @throws InvalidTokenException    token không hợp lệ
     * @throws ExternalServiceException không thể liên hệ Google để lấy public key
     */
    public GoogleUserInfo verifyIdToken(String idToken) {
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException e) {
            // Lỗi chữ ký, aud, hoặc format JWT không đúng
            log.warn("Google ID Token verification failed (security): {}", e.getMessage());
            throw new InvalidTokenException("Google ID Token không hợp lệ hoặc đã hết hạn.", e);
        } catch (IOException e) {
            // Không thể tải public key từ Google (mạng, timeout…)
            log.error("Cannot reach Google to verify ID Token: {}", e.getMessage());
            throw new ExternalServiceException("Không thể kết nối tới Google để xác minh token. Vui lòng thử lại sau.", e);
        } catch (IllegalArgumentException e) {
            // Token rỗng, null, hoặc base64 không hợp lệ
            log.warn("Google ID Token has invalid format: {}", e.getMessage());
            throw new InvalidTokenException("Định dạng Google ID Token không hợp lệ.", e);
        }

        if (token == null) {
            // verifier.verify() trả null khi signature/aud/expiry không hợp lệ
            log.warn("Google ID Token failed verification (null result)");
            throw new InvalidTokenException("Google ID Token không hợp lệ hoặc đã hết hạn.");
        }

        Payload payload = token.getPayload();

        // Kiểm tra email đã được Google xác minh
        Boolean emailVerified = payload.getEmailVerified();
        if (emailVerified == null || !emailVerified) {
            log.warn("Google account email not verified for sub={}", payload.getSubject());
            throw new InvalidTokenException("Email Google chưa được xác minh.");
        }

        return new GoogleUserInfo(
                payload.getSubject(),                      // sub — Google unique user ID
                payload.getEmail(),                        // email
                (String) payload.get("name"),              // name
                (String) payload.get("picture")            // picture
        );
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
