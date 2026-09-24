package com.vvttai.smart_link_shortener.modules.auth.dto;

public record ChangePasswordRequest (
        String currentPassword,
        String newPassword,
        String confirmationPassword)
{
}
