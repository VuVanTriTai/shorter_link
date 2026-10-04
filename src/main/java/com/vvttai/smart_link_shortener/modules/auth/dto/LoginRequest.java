package com.vvttai.smart_link_shortener.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

//DTO
public record LoginRequest(
        @NotBlank(message = "username không được để trống")
        String username,
        @NotBlank(message = "password không được để trống")
        String password
) {}