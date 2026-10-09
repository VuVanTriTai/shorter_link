package com.vvttai.smart_link_shortener.common.exception;

/**
 * Ném ra khi không thể kết nối tới dịch vụ bên ngoài (ví dụ: Google API).
 * Được map tới HTTP 503 Service Unavailable.
 */
public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
