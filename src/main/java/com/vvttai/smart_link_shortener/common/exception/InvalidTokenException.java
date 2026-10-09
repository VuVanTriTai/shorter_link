package com.vvttai.smart_link_shortener.common.exception;

/**
 * Ném ra khi Google ID Token không hợp lệ, sai chữ ký, hoặc sai audience.
 * Được map tới HTTP 401 Unauthorized.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
