package com.vvttai.smart_link_shortener.modules.auth.controller;

import com.vvttai.smart_link_shortener.modules.auth.dto.AuthResponse;
import com.vvttai.smart_link_shortener.modules.auth.dto.AuthTokens;
import com.vvttai.smart_link_shortener.modules.auth.dto.ChangePasswordRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.LoginRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.RegisterRequest;
import com.vvttai.smart_link_shortener.modules.auth.service.AuthService;
import com.vvttai.smart_link_shortener.common.exception.ExternalServiceException;
import com.vvttai.smart_link_shortener.common.exception.InvalidTokenException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            authService.register(request);
            return ResponseEntity.ok(Map.of("message", "register successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        try {
            AuthTokens tokens = authService.login(request);
            setRefreshTokenCookie(response, tokens.refreshToken(), authService.getRefreshTokenExpirationSeconds());
            return ResponseEntity.ok(new AuthResponse(tokens.accessToken(), "Bearer"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "Tài khoản hoặc mật khẩu không chính xác!"
            ));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            AuthTokens tokens = authService.refreshToken(refreshToken);
            setRefreshTokenCookie(response, tokens.refreshToken(), authService.getRefreshTokenExpirationSeconds());
            return ResponseEntity.ok(new AuthResponse(tokens.accessToken(), "Bearer"));
        } catch (LockedException e) {
            // Tài khoản bị khoá khi đang refresh — trả 403
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        clearRefreshTokenCookie(response);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * Đăng nhập/Đăng ký bằng Google OAuth 2.0.
     * Frontend gửi Google ID Token (lấy từ Google Identity Services SDK).
     * Backend xác minh token → tìm/tạo user → trả JWT.
     *
     * <ul>
     *   <li>Token sai / hết hạn → 401 Unauthorized</li>
     *   <li>Không thể kết nối Google → 503 Service Unavailable</li>
     * </ul>
     */
    @PostMapping("/google")
    public ResponseEntity<?> loginWithGoogle(@RequestBody Map<String, String> body, HttpServletResponse response) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Google ID Token is required"));
        }
        try {
            AuthTokens tokens = authService.loginWithGoogle(idToken);
            setRefreshTokenCookie(response, tokens.refreshToken(), authService.getRefreshTokenExpirationSeconds());
            return ResponseEntity.ok(new AuthResponse(tokens.accessToken(), "Bearer"));
        } catch (InvalidTokenException e) {
            // Token không hợp lệ, sai chữ ký, sai aud, hoặc hết hạn
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", e.getMessage()));
        } catch (ExternalServiceException e) {
            // Không thể kết nối tới Google (mạng, timeout...)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", e.getMessage()));
        } catch (LockedException e) {
            // Tài khoản bị khoá — Google token hợp lệ nhưng user bị admin block
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request, Principal connectedUser) {
        if (connectedUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Vui lòng đăng nhập để thực hiện!"));
        }
        try {
            authService.changePassword(request, connectedUser);
            return ResponseEntity.ok(Map.of("message", "Đổi mật khẩu thành công!"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Có lỗi xảy ra: " + e.getMessage()));
        }
    }

    // ======================== Cookie Helper Methods ========================

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false) // Đặt true khi deploy production có HTTPS
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(false)
                .path("/api/auth")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
