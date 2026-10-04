package com.vvttai.smart_link_shortener.modules.auth.service;

import com.vvttai.smart_link_shortener.common.util.JwtTokenProvider;
import com.vvttai.smart_link_shortener.modules.auth.dto.AuthTokens;
import com.vvttai.smart_link_shortener.modules.auth.dto.ChangePasswordRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.LoginRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.RegisterRequest;
import com.vvttai.smart_link_shortener.modules.auth.service.GoogleTokenVerifierService.GoogleUserInfo;
import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final GoogleTokenVerifierService googleTokenVerifierService;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider,
            RefreshTokenService refreshTokenService,
            GoogleTokenVerifierService googleTokenVerifierService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.googleTokenVerifierService = googleTokenVerifierService;
    }

    public void register(RegisterRequest request) {
        if (userRepository.existsUserByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists!");
        }
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password())
        );
        userRepository.save(user);
    }

    public AuthTokens login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        String username = authentication.getName();
        String accessToken = tokenProvider.generateToken(username);
        String refreshToken = refreshTokenService.createRefreshToken(username);
        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * Đăng nhập/Đăng ký bằng Google OAuth 2.0.
     *
     * Luồng:
     * 1. Xác minh Google ID Token qua Google tokeninfo API
     * 2. Tìm user theo providerId (Google sub) → nếu có → đăng nhập
     * 3. Tìm user theo email → nếu có → liên kết tài khoản Google vào user hiện tại
     * 4. Nếu hoàn toàn mới → tạo tài khoản mới
     * 5. Tạo JWT access token + refresh token
     */
    @Transactional
    public AuthTokens loginWithGoogle(String googleIdToken) {
        // 1. Xác minh ID Token
        GoogleUserInfo googleUser = googleTokenVerifierService.verifyIdToken(googleIdToken);
        if (googleUser == null) {
            throw new IllegalArgumentException("Google ID Token không hợp lệ hoặc đã hết hạn.");
        }

        // 2. Tìm user đã liên kết Google trước đó
        Optional<User> existingByProvider = userRepository.findByAuthProviderAndProviderId("GOOGLE", googleUser.sub());
        User user;

        if (existingByProvider.isPresent()) {
            // Đã có tài khoản Google → đăng nhập
            user = existingByProvider.get();
            log.info("Google login: existing user={}", user.getUsername());
        } else {
            // 3. Tìm user theo email (có thể đã đăng ký thường trước đó)
            Optional<User> existingByEmail = userRepository.findByEmail(googleUser.email());

            if (existingByEmail.isEmpty()) {
                // Tìm thêm theo username = email (trường hợp user cũ chưa có field email)
                existingByEmail = userRepository.findByUsername(googleUser.email());
            }

            if (existingByEmail.isPresent()) {
                // Liên kết Google vào tài khoản LOCAL hiện có
                user = existingByEmail.get();
                user.setAuthProvider("GOOGLE");
                user.setProviderId(googleUser.sub());
                if (user.getEmail() == null) {
                    user.setEmail(googleUser.email());
                }
                if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
                    user.setDisplayName(googleUser.name());
                }
                if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
                    user.setAvatarUrl(googleUser.picture());
                }
                userRepository.save(user);
                log.info("Google login: linked Google to existing user={}", user.getUsername());
            } else {
                // 4. Tạo tài khoản mới hoàn toàn
                user = new User();
                user.setUsername(googleUser.email()); // Dùng email làm username
                user.setEmail(googleUser.email());
                user.setAuthProvider("GOOGLE");
                user.setProviderId(googleUser.sub());
                user.setDisplayName(googleUser.name());
                user.setAvatarUrl(googleUser.picture());
                // OAuth user không cần password
                user.setPassword(null);
                userRepository.save(user);
                log.info("Google login: created new user={}", user.getUsername());
            }
        }

        // 5. Tạo JWT tokens
        String accessToken = tokenProvider.generateToken(user.getUsername());
        String refreshToken = refreshTokenService.createRefreshToken(user.getUsername());
        return new AuthTokens(accessToken, refreshToken);
    }

    public AuthTokens refreshToken(String oldRefreshToken) {
        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is missing");
        }
        String username = refreshTokenService.validateAndGetUsername(oldRefreshToken);
        if (username == null) {
            throw new IllegalArgumentException("Refresh token is invalid or expired");
        }

        // Xoay vòng (Rotate) Refresh Token
        refreshTokenService.deleteRefreshToken(oldRefreshToken);
        String newRefreshToken = refreshTokenService.createRefreshToken(username);
        String newAccessToken = tokenProvider.generateToken(username);

        return new AuthTokens(newAccessToken, newRefreshToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.deleteRefreshToken(refreshToken);
        }
    }

    public void changePassword(ChangePasswordRequest request, Principal connectedUser) {
        String username = connectedUser.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // OAuth users that haven't set a password cannot use change-password
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new IllegalArgumentException("Tài khoản đăng nhập bằng Google không có mật khẩu để đổi. "
                    + "Vui lòng sử dụng chức năng đặt mật khẩu trước.");
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Mật khẩu hiện tại không chính xác!");
        }
        if (request.newPassword() == null || request.newPassword().length() < 6) {
            throw new IllegalArgumentException("Mật khẩu mới phải có tối thiểu 6 ký tự!");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Mật khẩu mới không được trùng với mật khẩu hiện tại!");
        }
        if (!request.newPassword().equals(request.confirmationPassword())) {
            throw new IllegalArgumentException("Mật khẩu mới và mật khẩu xác nhận không khớp!");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenService.getRefreshTokenExpirationSeconds();
    }
}

