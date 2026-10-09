package com.vvttai.smart_link_shortener.modules.link.controller;

import com.vvttai.smart_link_shortener.modules.link.service.QrCodeService;
import com.google.zxing.WriterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/**
 * QR Code endpoints:
 *
 *   GET  /api/qr/{shortCode}          → trả về PNG binary (dùng trong <img src>)
 *   GET  /api/qr/{shortCode}/base64   → trả về JSON { "dataUri": "data:image/png;base64,..." }
 *
 * Endpoint PNG không yêu cầu xác thực (public) — để browser/img tag gọi trực tiếp.
 * Endpoint Base64 yêu cầu đăng nhập (chỉ owner mới cần) — bảo vệ bởi SecurityConfig.
 */
@RestController
@RequestMapping("/api/qr")
public class QrCodeController {

    private static final Logger log = LoggerFactory.getLogger(QrCodeController.class);

    private final QrCodeService qrCodeService;

    /**
     * Base URL của ứng dụng (ví dụ: https://short.example.com).
     * Không hardcode localhost để QR code hoạt động đúng trên mọi môi trường.
     */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public QrCodeController(QrCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    /**
     * Trả về QR code dạng PNG binary.
     * Public — không cần token (dùng trực tiếp như URL ảnh).
     *
     * Query params:
     *   size  — kích thước ảnh vuông (px), mặc định 300, tối đa 1000.
     */
    @GetMapping(value = "/{shortCode}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrPng(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "300") int size) {

        size = Math.max(50, Math.min(size, 1000));

        try {
            String shortUrl = baseUrl + "/r/" + shortCode;
            byte[] png = qrCodeService.generatePng(shortUrl, size, size);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("public, max-age=3600");

            return new ResponseEntity<>(png, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("QR generation failed for shortCode={}: {}", shortCode, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Trả về QR code dạng Base64 data URI — dùng trong React state/modal.
     * Yêu cầu xác thực (Bearer token) — bảo vệ bởi SecurityConfig (/api/qr/{shortCode}/base64 không permitAll).
     */
    @GetMapping("/{shortCode}/base64")
    public ResponseEntity<Map<String, String>> getQrBase64(
            @PathVariable String shortCode,
            Principal principal) {

        try {
            String shortUrl = baseUrl + "/r/" + shortCode;
            String dataUri  = qrCodeService.generateBase64DataUri(shortUrl);

            return ResponseEntity.ok(Map.of(
                    "shortCode", shortCode,
                    "shortUrl",  shortUrl,
                    "dataUri",   dataUri
            ));

        } catch (Exception e) {
            log.error("QR base64 generation failed for shortCode={}: {}", shortCode, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

