package com.vvttai.smart_link_shortener.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String REFRESH_TOKEN_PREFIX = "refreshtoken:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.refresh-token-expiration:604800}")
    private long refreshTokenExpirationSeconds;

    public RefreshTokenService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Sinh UUID Refresh Token ngẫu nhiên và lưu vào Redis với key refreshtoken:<token> -> username.
     * TTL = refreshTokenExpirationSeconds (mặc định 7 ngày).
     */
    public String createRefreshToken(String username) {
        String token = UUID.randomUUID().toString();
        String key = REFRESH_TOKEN_PREFIX + token;
        stringRedisTemplate.opsForValue().set(key, username, refreshTokenExpirationSeconds, TimeUnit.SECONDS);
        log.debug("Created RefreshToken for user={}", username);
        return token;
    }

    /**
     * Kiểm tra Refresh Token trong Redis (READ-ONLY, không xoá).
     * Dùng cho trường hợp chỉ cần đọc username mà không consume token.
     * <p>
     * <b>Lưu ý:</b> Không dùng cặp này + {@link #deleteRefreshToken} để rotate token
     * vì không atomic. Dùng {@link #consumeRefreshToken} thay thế.
     */
    public String validateAndGetUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = REFRESH_TOKEN_PREFIX + token;
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * Lấy username và xoá Refresh Token trong <b>một thao tác atomic (Redis GETDEL)</b>.
     * <p>
     * Dùng cho token rotation: đảm bảo chỉ có đúng một request thắng khi
     * nhiều request đồng thời cố dùng cùng một refresh token.
     *
     * @param token refresh token cần consume
     * @return username nếu token hợp lệ; {@code null} nếu token đã bị consume hoặc không tồn tại
     */
    public String consumeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // getAndDelete = Redis GETDEL — atomic: lấy giá trị và xoá key trong một lệnh
        return stringRedisTemplate.opsForValue().getAndDelete(REFRESH_TOKEN_PREFIX + token);
    }

    /**
     * Xóa Refresh Token trong Redis khi logout hoặc xoay vòng (rotate) token.
     */
    public void deleteRefreshToken(String token) {
        if (token != null && !token.isBlank()) {
            String key = REFRESH_TOKEN_PREFIX + token;
            stringRedisTemplate.delete(key);
            log.debug("Deleted RefreshToken from Redis");
        }
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }
}
