package com.vvttai.smart_link_shortener.common.exception;

/**
 * Ném khi link đã bị chủ sở hữu tạm tắt/vô hiệu hóa (active = false).
 */
public class LinkInactiveException extends RuntimeException {
    public LinkInactiveException(String shortCode) {
        super("Liên kết này hiện đang tạm dừng hoạt động: " + shortCode);
    }
}
