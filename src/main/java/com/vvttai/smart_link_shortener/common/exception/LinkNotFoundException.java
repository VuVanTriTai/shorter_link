package com.vvttai.smart_link_shortener.common.exception;

/**
 * Ném khi không tìm thấy link theo shortCode.
 */
public class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException(String shortCode) {
        super("Link không tồn tại: " + shortCode);
    }
}
