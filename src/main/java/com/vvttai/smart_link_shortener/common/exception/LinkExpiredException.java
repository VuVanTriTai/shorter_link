package com.vvttai.smart_link_shortener.common.exception;

/**
 * Ném khi link đã vượt quá thời hạn sử dụng (expiresAt).
 */
public class LinkExpiredException extends RuntimeException {
    public LinkExpiredException(String shortCode) {
        super("Link đã hết hạn: " + shortCode);
    }
}
