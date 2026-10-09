package com.vvttai.smart_link_shortener.modules.user.service;

import com.vvttai.smart_link_shortener.common.util.JwtTokenProvider;
import com.vvttai.smart_link_shortener.modules.auth.dto.AuthResponse;
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
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuthenticationManager authenticationManager;

    public UserService(PasswordEncoder passwordEncoder,
                        UserRepository userRepository,
                        JwtTokenProvider tokenProvider,
                        AuthenticationManager authenticationManager) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.authenticationManager = authenticationManager;
    }

    public void register(RegisterRequest request) {
        if (userRepository.existsUserByUsernameAndDeletedFalse(request.username())) {
            throw new IllegalArgumentException("username areadly exist!");
        }

        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password())
        );
        userRepository.save(user);
    }

    public String authenticate(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        return authentication.getName();
    }

    public AuthResponse login(LoginRequest request) {
        String username = authenticate(request);
        String token = tokenProvider.generateToken(username);
        return new AuthResponse(token, "Bearer");
    }

    public void changePassword(ChangePasswordRequest request, Principal connectedUser) {
        String username = connectedUser.getName();
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new RuntimeException("user not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Wrong current pass/pass not true");
        }
        if (!request.newPassword().equals(request.confirmationPassword())) {
            throw new IllegalArgumentException("new password do not match");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }
}
