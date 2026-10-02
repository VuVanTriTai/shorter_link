package com.vvttai.smart_link_shortener.modules.auth.service;

import com.vvttai.smart_link_shortener.common.util.JwtTokenProvider;
import com.vvttai.smart_link_shortener.modules.auth.dto.AuthTokens;
import com.vvttai.smart_link_shortener.modules.auth.dto.ChangePasswordRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.LoginRequest;
import com.vvttai.smart_link_shortener.modules.auth.dto.RegisterRequest;
import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.Principal;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider tokenProvider,
            RefreshTokenService refreshTokenService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
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

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Wrong current password!");
        }
        if (!request.newPassword().equals(request.confirmationPassword())) {
            throw new IllegalArgumentException("New passwords do not match!");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenService.getRefreshTokenExpirationSeconds();
    }
}
