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
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
        // Chỉ kiểm tra username chưa bị xoá — cho phép tái đăng ký sau soft-delete
        if (userRepository.existsUserByUsernameAndDeletedFalse(request.username())) {
            throw new IllegalArgumentException("Username already exists!");
        }
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password())
        );
        userRepository.save(user);
    }

    public AuthTokens login(LoginRequest request) {
        // AuthenticationManager gọi CustomUserDetailService.loadUserByUsername bên trong
        // — nếu deleted hoặc locked, sẽ ném UsernameNotFoundException / LockedException tự động
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        String username = authentication.getName();

        // Lấy role từ DB để nhúng vào JWT
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        String role = user.getRoles();

        String accessToken = tokenProvider.generateToken(username, role);
        String refreshToken = refreshTokenService.createRefreshToken(username);
        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * Đăng nhập/Đăng ký bằng Google OAuth 2.0.
     *
     * Luồng:
     * 1. Xác minh Google ID Token bằng google-api-client (offline, cache public key)
     * 2. Tìm user theo providerId (Google sub) → nếu có → đăng nhập
     * 3. Tìm user theo email → nếu có → liên kết tài khoản Google vào user hiện tại
     * 4. Nếu hoàn toàn mới → tạo tài khoản mới
     * 5. Tạo JWT access token + refresh token
     *
     * @throws com.vvttai.smart_link_shortener.common.exception.InvalidTokenException    token sai / hết hạn → 401
     * @throws com.vvttai.smart_link_shortener.common.exception.ExternalServiceException Google down / network lỗi → 503
     */
    @Transactional
    public AuthTokens loginWithGoogle(String googleIdToken) {
        // 1. Xác minh ID Token — throws InvalidTokenException hoặc ExternalServiceException
        GoogleUserInfo googleUser = googleTokenVerifierService.verifyIdToken(googleIdToken);

        // 2. Tìm user đã liên kết Google trước đó (bỏ qua user đã soft-delete)
        Optional<User> existingByProvider = userRepository
                .findByAuthProviderAndProviderIdAndDeletedFalse("GOOGLE", googleUser.sub());
        User user;

        if (existingByProvider.isPresent()) {
            // Đã có tài khoản Google → đăng nhập
            user = existingByProvider.get();
            log.info("Google login: existing user={}", user.getUsername());
        } else {
            // 3. Tìm user theo email (có thể đã đăng ký thường trước đó, bỏ qua deleted)
            Optional<User> existingByEmail = userRepository.findByEmailAndDeletedFalse(googleUser.email());

            if (existingByEmail.isEmpty()) {
                // Tìm thêm theo username = email (trường hợp user cũ chưa có field email)
                existingByEmail = userRepository.findByUsernameAndDeletedFalse(googleUser.email());
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
                user.setPassword(null); // OAuth user không cần password
                userRepository.save(user);
                log.info("Google login: created new user={}", user.getUsername());
            }
        }

        // Guard: tài khoản bị khoá — từ chối đăng nhập dù token hợp lệ
        if (user.isLocked()) {
            log.warn("Google login rejected: user={} is locked", user.getUsername());
            throw new LockedException("Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
        }

        // 5. Tạo JWT tokens (nhúc role vào claim)
        String accessToken = tokenProvider.generateToken(user.getUsername(), user.getRoles());
        String refreshToken = refreshTokenService.createRefreshToken(user.getUsername());
        return new AuthTokens(accessToken, refreshToken);
    }

    public AuthTokens refreshToken(String oldRefreshToken) {
        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is missing");
        }

        // consumeRefreshToken = Redis GETDEL: lấy username và xoá token trong một lệnh atomic.
        // Nếu 2 request đồng thời gửi cùng token, chỉ 1 request nhận được username, request kia nhận null.
        String username = refreshTokenService.consumeRefreshToken(oldRefreshToken);
        if (username == null) {
            throw new IllegalArgumentException("Refresh token is invalid or expired");
        }

        // Lấy role mới nhất từ DB — chỉ dùng user chưa bị soft-delete
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Tài khoản không tồn tại hoặc đã bị xoá: " + username));

        // Tài khoản bị khoá — token đã bị consume ở trên (GETDEL), không cấp token mới
        if (user.isLocked()) {
            log.warn("Refresh token rejected: user={} is locked", username);
            throw new LockedException("Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
        }

        // Cấp refresh token mới (Rotate)
        String newRefreshToken = refreshTokenService.createRefreshToken(username);
        String newAccessToken = tokenProvider.generateToken(username, user.getRoles());

        return new AuthTokens(newAccessToken, newRefreshToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.deleteRefreshToken(refreshToken);
        }
    }

    public void changePassword(ChangePasswordRequest request, Principal connectedUser) {
        String username = connectedUser.getName();
        // Chỉ dùng user chưa bị soft-delete
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new UsernameNotFoundException("Tài khoản không tồn tại: " + username));

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

