package com.vvttai.smart_link_shortener.modules.link.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    private static final int DEFAULT_WIDTH  = 300;
    private static final int DEFAULT_HEIGHT = 300;

    private static final MatrixToImageConfig IMAGE_CONFIG =
            new MatrixToImageConfig(0xFF000000, 0xFFFFFFFF);

    public byte[] generatePng(String url, int width, int height) throws WriterException, IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(url, BarcodeFormat.QR_CODE, width, height, hints);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos, IMAGE_CONFIG);
            return baos.toByteArray();
        }
    }

    public byte[] generatePng(String url) throws WriterException, IOException {
        return generatePng(url, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public String generateBase64DataUri(String url) throws WriterException, IOException {
        byte[] pngBytes = generatePng(url);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
    }
}