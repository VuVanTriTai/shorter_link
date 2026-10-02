//sinh và validate token
package com.vvttai.smart_link_shortener.common.util;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {
    @Value("${jwt.secret:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration:${jwt.expiration:900000}}")
    private long jwtExpiration;

    private SecretKey getSigningKey() {
        String secret = (jwtSecret != null && !jwtSecret.isBlank())
                ? jwtSecret.trim()
                : "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private long getExpirationTime() {
        return jwtExpiration > 0 ? jwtExpiration : 900000L;
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + getExpirationTime());

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SignatureException e) {
            System.err.println("=== Lỗi: Chữ ký Token không hợp lệ ===");
        } catch (MalformedJwtException e) {
            System.err.println("=== Lỗi: Cấu trúc Token không đúng định dạng ===");
        } catch (ExpiredJwtException e) {
            System.err.println("=== Lỗi: Token đã hết hạn ===");
        } catch (JwtException | IllegalArgumentException e) {
            System.err.println("=== Lỗi Token: " + e.getMessage());
        }
        return false;
    }
}
