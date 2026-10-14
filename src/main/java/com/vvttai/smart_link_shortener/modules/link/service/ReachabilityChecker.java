package com.vvttai.smart_link_shortener.modules.link.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thành phần kiểm tra khả năng phản hồi (Reachability) của URL đích:
 * - SSRF Hardening: Sử dụng IP đã resolve từ SsrfGuard để kết nối trực tiếp (chống DNS rebinding).
 * - Redirect Control: Kiểm tra URL đích khi server redirect (chống redirect tới IP nội bộ).
 * - Hiệu năng: HttpClient sử dụng Executor riêng biệt với daemon threads.
 * - UX & Khả năng chịu lỗi: Không ném lỗi cứng nhắc khi server đích phản hồi chậm hoặc mạng nghẽn.
 *   Trả về trạng thái UNVERIFIED để cho phép người dùng tiếp tục tạo link trừ khi bật strict mode.
 */
@Component
public class ReachabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ReachabilityChecker.class);

    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    @Value("${app.security.strict-reachability:false}")
    private boolean strictReachability;

    public enum ReachabilityStatus {
        VERIFIED,
        UNVERIFIED
    }

    public ReachabilityChecker(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;

        this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "reachability-http-" + count.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Ping kiểm tra URL đích.
     * @return ReachabilityStatus (VERIFIED hoặc UNVERIFIED)
     * @throws IllegalArgumentException nếu phát hiện nguy cơ bảo mật SSRF khi redirect.
     */
    public ReachabilityStatus checkReachability(URI originalUri, InetAddress resolvedAddress) {
        try {
            // Xây dựng URI kết nối thẳng vào IP đã resolve (chống DNS rebinding)
            URI connectUri;
            if (resolvedAddress != null) {
                String ipStr = resolvedAddress.getHostAddress();
                if (resolvedAddress instanceof java.net.Inet6Address) {
                    ipStr = "[" + ipStr + "]";
                }
                int port = originalUri.getPort();
                if (port == -1) {
                    port = "https".equalsIgnoreCase(originalUri.getScheme()) ? 443 : 80;
                }
                String path = originalUri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                String query = originalUri.getRawQuery();
                String connectUrl = originalUri.getScheme() + "://" + ipStr + ":" + port + path;
                if (query != null) connectUrl += "?" + query;
                connectUri = URI.create(connectUrl);
            } else {
                connectUri = originalUri;
            }

            // Gửi HEAD request trước (nhẹ và nhanh)
            HttpRequest.Builder headBuilder = HttpRequest.newBuilder()
                    .uri(connectUri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                    .timeout(Duration.ofSeconds(3));

            if (resolvedAddress != null) {
                String originalHost = originalUri.getHost();
                int originalPort = originalUri.getPort();
                if (originalPort != -1 && originalPort != 80 && originalPort != 443) {
                    originalHost += ":" + originalPort;
                }
                headBuilder.header("Host", originalHost);
            }

            HttpResponse<Void> response = httpClient.send(
                    headBuilder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            // Nếu server redirect (3xx), kiểm tra địa chỉ redirect
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null && !location.isBlank()) {
                    ssrfGuard.validateRedirectTarget(location, originalUri);
                }
                return ReachabilityStatus.VERIFIED;
            }

            // Nếu server không hỗ trợ HEAD (405 Method Not Allowed), thử gửi GET
            if (status == 405) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                        .uri(connectUri)
                        .GET()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SmartLinkBot/1.0")
                        .timeout(Duration.ofSeconds(3));

                if (resolvedAddress != null) {
                    String originalHost = originalUri.getHost();
                    int originalPort = originalUri.getPort();
                    if (originalPort != -1 && originalPort != 80 && originalPort != 443) {
                        originalHost += ":" + originalPort;
                    }
                    getBuilder.header("Host", originalHost);
                }

                response = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();

                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location != null && !location.isBlank()) {
                        ssrfGuard.validateRedirectTarget(location, originalUri);
                    }
                }
            }

            if (status >= 500 && status <= 504) {
                log.warn("Target URL server returned error status {}: {}", status, originalUri);
            }
            return ReachabilityStatus.VERIFIED;

        } catch (IllegalArgumentException e) {
            // Lỗi SSRF bảo mật (ví dụ redirect trỏ vào private IP) -> bắt buộc chặn
            throw e;
        } catch (Exception e) {
            // Lỗi mạng, timeout, hoặc server đích phản hồi chậm
            log.warn("Reachability check failed for URL {}: {} (strictMode={})",
                    originalUri, e.getMessage(), strictReachability);

            if (strictReachability) {
                throw new IllegalArgumentException(
                        "Không thể kết nối đến URL đích (Máy chủ không phản hồi hoặc tên miền không hợp lệ)!");
            }
            // Không từ chối người dùng; ghi nhận trạng thái chưa xác minh để cải thiện trải nghiệm
            return ReachabilityStatus.UNVERIFIED;
        }
    }

    @PreDestroy
    public void destroy() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
