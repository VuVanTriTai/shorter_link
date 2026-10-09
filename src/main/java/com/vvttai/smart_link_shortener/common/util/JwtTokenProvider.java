//sinh và validate token
package com.vvttai.smart_link_shortener.common.util;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration:${jwt.expiration:900000}}")
    private long jwtExpiration;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "❌ JWT_SECRET chưa được cấu hình! " +
                "Hãy đặt biến môi trường JWT_SECRET hoặc cấu hình jwt.secret trong application.properties"
            );
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException(
                "❌ JWT_SECRET quá ngắn! Cần ít nhất 32 ký tự để đảm bảo bảo mật."
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.trim().getBytes(StandardCharsets.UTF_8));
        log.info("✅ JWT được cấu hình thành công (secret length: {} chars)", jwtSecret.length());
    }

    private long getExpirationTime() {
        return jwtExpiration > 0 ? jwtExpiration : 900000L;
    }

    /**
     * Tạo JWT token với role claim.
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + getExpirationTime());

        // Chuẩn hoá role: đảm bảo luôn có prefix ROLE_
        String normalizedRole = (role != null && !role.isBlank()) ? role : "ROLE_USER";
        if (!normalizedRole.startsWith("ROLE_")) {
            normalizedRole = "ROLE_" + normalizedRole.toUpperCase();
        }

        return Jwts.builder()
                .subject(username)
                .claim("role", normalizedRole)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Backward-compatible: mặc định role = ROLE_USER.
     */
    public String generateToken(String username) {
        return generateToken(username, "ROLE_USER");
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Đọc role claim từ JWT token.
     */
    public String getRoleFromToken(String token) {
        String role = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
        return (role != null && !role.isBlank()) ? role : "ROLE_USER";
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SignatureException e) {
            log.warn("Chữ ký Token không hợp lệ");
        } catch (MalformedJwtException e) {
            log.warn("Cấu trúc Token không đúng định dạng");
        } catch (ExpiredJwtException e) {
            log.warn("Token đã hết hạn");
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Lỗi Token: {}", e.getMessage());
        }
        return false;
    }
}

