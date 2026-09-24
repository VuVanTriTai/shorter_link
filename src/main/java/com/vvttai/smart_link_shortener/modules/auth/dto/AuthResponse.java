package com.vvttai.smart_link_shortener.modules.auth.dto;
//DTO
public record AuthResponse(String accessToken, String tokenType) {
    public AuthResponse(String accessToken) {
        this(accessToken, "Bearer");
    }
}