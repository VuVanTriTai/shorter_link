package com.vvttai.smart_link_shortener.modules.link.service;

import com.vvttai.smart_link_shortener.common.exception.LinkExpiredException;
import com.vvttai.smart_link_shortener.common.exception.LinkInactiveException;
import com.vvttai.smart_link_shortener.common.exception.LinkNotFoundException;
import com.vvttai.smart_link_shortener.modules.link.dto.CreateLinkRequest;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkCacheDto;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkResponse;
import com.vvttai.smart_link_shortener.modules.link.dto.UpdateLinkRequest;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.analytics.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    // Số lần retry tối đa khi sinh mã random gặp collision (56 tỷ khả năng → xác suất cần retry rất thấp)
    private static final int MAX_CODE_RETRIES = 5;
    private final SecureRandom random = new SecureRandom();

    private final LinkRepository linkRepository;
    private final UserRepository userRepository;
    private final LinkCacheService linkCacheService;
    private final UrlValidationService urlValidationService;
    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final AnalyticsService analyticsService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public LinkService(
            LinkRepository linkRepository,
            UserRepository userRepository,
            LinkCacheService linkCacheService,
            UrlValidationService urlValidationService,
            ClickAnalyticsRepository clickAnalyticsRepository,
            AnalyticsService analyticsService,
            PasswordEncoder passwordEncoder) {
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.linkCacheService = linkCacheService;
        this.urlValidationService = urlValidationService;
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.analyticsService = analyticsService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public LinkResponse createShortLink(CreateLinkRequest request, Principal connectedUser) {
        // 1. Kiểm tra an toàn và xác thực URL đích (Scheme, Self-loop, SSRF,
        // Reachability, Safe Browsing)
        urlValidationService.validateUrl(request.originalUrl());

        User user = userRepository.findByUsername(connectedUser.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + connectedUser.getName()));

        // Chuẩn bị thông tin chung cho Link
        Link link = new Link(request.originalUrl(), null, user);
        if (request.expiresAt() != null) {
            if (Instant.now().isAfter(request.expiresAt())) {
                throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
            }
            link.setExpiresAt(request.expiresAt());
        }
        if (request.password() != null && !request.password().trim().isEmpty()) {
            link.setPassword(passwordEncoder.encode(request.password().trim()));
        }

        if (request.customCode() != null && !request.customCode().isBlank()) {
            // --- Nhánh Custom Code ---
            String customCode = request.customCode().trim();
            // 2. Kiểm tra tính hợp lệ của mã custom (ký tự, độ dài, blacklist)
            urlValidationService.validateCustomCode(customCode);
            link.setShortCode(customCode);
            try {
                // Dùng saveAndFlush để bắt DataIntegrityViolationException ngay trong method
                // thay vì để exception nổi lên tới controller khi transaction commit
                linkRepository.saveAndFlush(link);
            } catch (DataIntegrityViolationException e) {
                throw new IllegalArgumentException("Mã custom short code '" + customCode + "' đã tồn tại, vui lòng chọn mã khác!");
            }
            // Xóa negative cache phòng trường hợp mã này từng bị truy cập khi chưa tồn tại
            registerCacheEvictAfterCommit(customCode);
        } else {
            // --- Nhánh Random Code với retry có giới hạn ---
            link = saveWithRandomCode(link);
            registerCacheEvictAfterCommit(link.getShortCode());
        }

        return mapToResponse(link);
    }

    @Transactional
    public LinkResponse updateShortLink(String currentShortCode, UpdateLinkRequest request, String username) {
        // 1. Kiểm tra an toàn và xác thực URL đích mới
        urlValidationService.validateUrl(request.originalUrl());

        // Tìm link theo shortCode hiện tại + kiểm tra chính chủ
        Link link = linkRepository.findByShortCodeAndUserUsername(currentShortCode, username)
                .orElseThrow(() -> new IllegalArgumentException("Link không tồn tại hoặc bạn không có quyền sửa!"));

        if (link.isBanned()) {
            throw new IllegalArgumentException(
                    "Link này đã bị Quản trị viên khoá (Banned) do vi phạm quy định, bạn không thể chỉnh sửa!");
        }

        // Nếu người dùng muốn đổi sang customCode mới
        if (request.customCode() != null && !request.customCode().isBlank()) {
            String newCode = request.customCode().trim();
            // 2. Kiểm tra tính hợp lệ của mã custom mới
            urlValidationService.validateCustomCode(newCode);
            link.setShortCode(newCode);
        }

        link.setOriginalUrl(request.originalUrl());

        // 3. Cập nhật thời hạn hết hạn: kiểm tra tương lai và không ghi đè null khi client không gửi
        if (Boolean.TRUE.equals(request.removeExpiresAt())) {
            link.setExpiresAt(null);
        } else if (request.expiresAt() != null) {
            if (Instant.now().isAfter(request.expiresAt())) {
                throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
            }
            link.setExpiresAt(request.expiresAt());
        }

        if (request.active() != null) {
            link.setActive(request.active());
        }

        // Cập nhật mật khẩu bảo vệ
        if (Boolean.TRUE.equals(request.removePassword())) {
            link.setPassword(null);
        } else if (request.password() != null && !request.password().trim().isEmpty()) {
            link.setPassword(passwordEncoder.encode(request.password().trim()));
        }

        try {
            // saveAndFlush để bắt DataIntegrityViolationException ngay trong method
            // thay vì để exception nổi lên tới controller khi transaction commit
            linkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "Mã custom short code '" + link.getShortCode() + "' đã tồn tại, vui lòng chọn mã khác!");
        }

        // 4. Xóa cache SAU KHI Transaction commit thành công (tránh stale cache nếu có redirect đọc giữa chừng)
        String updatedShortCode = link.getShortCode();
        registerCacheEvictAfterCommit(currentShortCode, updatedShortCode);

        return mapToResponse(link);
    }

    public List<LinkResponse> getUserLinks(Principal connectedUser) {

        return linkRepository.findByUserUsername(connectedUser.getName())
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public void deleteLink(Long linkId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại"));

        // Xóa cache sau khi transaction commit thành công
        registerCacheEvictAfterCommit(link.getShortCode());
        clickAnalyticsRepository.deleteByLinkId(link.getId());
        linkRepository.delete(link);
    }

    /**
     * Lưu Link với short code random, retry tối đa MAX_CODE_RETRIES lần nếu gặp collision.
     * Tránh loop vô hạn và tránh race condition so với pattern check-then-save cũ.
     *
     * <p>Với 6 ký tự base62 (~56 tỷ tổ hợp), xác suất collision cực thấp.
     * Retry 5 lần là đủ an toàn cho mọi thực tế.
     */
    private Link saveWithRandomCode(Link link) {
        for (int attempt = 1; attempt <= MAX_CODE_RETRIES; attempt++) {
            link.setShortCode(generateRandomCode());
            try {
                return linkRepository.saveAndFlush(link);
            } catch (DataIntegrityViolationException e) {
                log.warn("Short code collision on attempt {}/{}: code={}", attempt, MAX_CODE_RETRIES, link.getShortCode());
                if (attempt == MAX_CODE_RETRIES) {
                    throw new IllegalStateException(
                            "Hệ thống không thể sinh mã rút gọn sau " + MAX_CODE_RETRIES + " lần thử. Vui lòng thử lại!");
                }
            }
        }
        // Không bao giờ tới đây nhưng compiler cần
        throw new IllegalStateException("Unexpected error generating short code");
    }

    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private LinkResponse mapToResponse(Link link) {
        String cleanBaseUrl = (baseUrl != null && baseUrl.endsWith("/"))
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : (baseUrl != null ? baseUrl : "http://localhost:8080");
        String fullShortUrl = cleanBaseUrl + "/r/" + link.getShortCode();
        return new LinkResponse(
                link.getId(),
                link.getOriginalUrl(),
                link.getShortCode(),
                fullShortUrl,
                link.getClickCount(),
                link.isActive(),
                link.isBanned(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.hasPassword());
    }

    /**
     * Xác thực mật khẩu truy cập của link (dùng BCrypt).
     * LUÔN đọc BCrypt hash từ DB (không dùng cache) để:
     * - Không lưu hash nhạy cảm trong Redis
     * - Đảm bảo hash luôn mới nhất khi user đổi password
     */
    public boolean verifyPassword(String shortCode, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        // Đọc hash trực tiếp từ DB
        Link dbLink = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));
        if (!dbLink.hasPassword()) {
            return true;
        }
        return passwordEncoder.matches(rawPassword.trim(), dbLink.getPassword());
    }

    /**
     * Tìm link theo shortCode, đồng thời kiểm tra:
     * 1. Link có tồn tại không -> ném LinkNotFoundException
     * 2. Link có bị vô hiệu hóa không -> ném LinkNotFoundException
     * 3. Link có hết hạn chưa -> ném LinkExpiredException
     *
     * Redis Cache: Check cache trước -> miss thì query DB rồi put vào cache (TTL =
     * 1 giờ).
     */
    public Link getLinkByShortCode(String shortCode) {
        // --- 1. Check Redis cache ---
        LinkCacheDto cached = linkCacheService.get(shortCode);
        if (cached != null) {
            log.debug("Cache HIT: shortCode={}", shortCode);

            // 1.1. Negative Cache HIT: link không tồn tại (chặn scan mã ngẫu nhiên đập DB)
            if (cached.isNotFound()) {
                throw new LinkNotFoundException(shortCode);
            }

            // 1.2. Validate trạng thái từ cache: bị khóa hoặc tắt
            if (cached.banned() || !cached.active()) {
                throw new LinkInactiveException(shortCode);
            }

            // 1.3. Validate thời hạn từ cache
            if (cached.expiresAt() != null && Instant.now().isAfter(cached.expiresAt())) {
                // KHÔNG evict cache ở đây! Giữ cache trong Redis để các request sau tiếp tục HIT cache và bị chặn ngay,
                // tránh việc evict xong request sau lại đập thẳng vào DB!
                throw new LinkExpiredException(shortCode);
            }

            Link link = cached.toEntity();
            // Đặt 1 giá trị sentinel để hasPassword() trả đúng khi gọi từ cache
            if (cached.hasPassword()) {
                link.setPassword("__CACHED_HAS_PASSWORD__");
            }
            return link;
        }

        // --- 2. Cache MISS -> Query DB ---
        log.debug("Cache MISS: shortCode={}", shortCode);
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseGet(() -> {
                    // Negative Caching: lưu trạng thái không tồn tại (TTL 60s) để chống scan mã ngẫu nhiên đập DB
                    linkCacheService.putNotFound(shortCode);
                    throw new LinkNotFoundException(shortCode);
                });

        // Kiểm tra link có bị khoá/tắt không
        if (link.isBanned() || !link.isActive()) {
            // Cache trạng thái để chống spam DB (TTL = 1 giờ)
            linkCacheService.put(link);
            throw new LinkInactiveException(shortCode);
        }

        // Kiểm tra link có hết hạn không
        if (link.getExpiresAt() != null && Instant.now().isAfter(link.getExpiresAt())) {
            // Cache trạng thái hết hạn với TTL ngắn (5 phút) để chống spam đập DB
            linkCacheService.putExpired(link);
            throw new LinkExpiredException(shortCode);
        }

        // --- 3. Put vào cache (TTL = 1 giờ) ---
        // LinkCacheDto.fromEntity sẽ lưu hasPassword=true/false, KHÔNG lưu hash
        linkCacheService.put(link);

        return link;
    }

    /**
     * Lấy original URL sau khi đã validate hết hạn và trạng thái.
     * Tái sử dụng getLinkByShortCode() để tránh query DB 2 lần.
     */
    public String getOriginalUrl(String shortCode) {
        return getLinkByShortCode(shortCode).getOriginalUrl();
    }

    /**
     * Lấy link để xem thống kê (Analytics).
     * Khác với getLinkByShortCode() dùng khi redirect, phương thức này KHÔNG ném
     * ngoại lệ
     * khi link đã hết hạn hoặc tạm tắt, cho phép người dùng vẫn xem được toàn bộ dữ
     * liệu thống kê của link.
     */
    public Link getLinkForStats(String shortCode) {
        // --- 1. Check Redis cache ---
        LinkCacheDto cached = linkCacheService.get(shortCode);
        if (cached != null) {
            if (cached.isNotFound()) {
                throw new LinkNotFoundException(shortCode);
            }
            log.debug("Cache HIT for stats: shortCode={}", shortCode);
            return cached.toEntity();
        }

        // --- 2. Cache MISS -> Query DB ---
        log.debug("Cache MISS for stats: shortCode={}", shortCode);
        return linkRepository.findByShortCode(shortCode)
                .orElseGet(() -> {
                    linkCacheService.putNotFound(shortCode);
                    throw new LinkNotFoundException(shortCode);
                });
    }

    /**
     * Lấy link để xem thống kê, có kiểm tra quyền sở hữu.
     * Chỉ chủ sở hữu (owner) mới được xem analytics của link.
     * Ngăn chặn IDOR: user A không xem được stats của link do user B tạo.
     */
    public Link getLinkForStatsOwnedBy(String shortCode, String username) {
        return linkRepository.findByShortCodeAndUserUsername(shortCode, username)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));
    }

    @Transactional
    public LinkResponse setExpirationDate(Long linkId, Instant expiresAt, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại"));

        if (link.isBanned()) {
            throw new IllegalArgumentException("Link này đã bị Quản trị viên khoá, không thể chỉnh sửa!");
        }

        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
        }
        link.setExpiresAt(expiresAt);
        linkRepository.save(link);

        // Xóa cache sau khi commit vì TTL đã thay đổi
        registerCacheEvictAfterCommit(link.getShortCode());

        return mapToResponse(link);
    }

    @Transactional
    public LinkResponse setActive(Long linkId, boolean active, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại hoặc bạn không có quyền sửa!"));

        if (link.isBanned()) {
            throw new IllegalArgumentException("Link này đã bị Quản trị viên khoá (Banned), không thể tự mở lại!");
        }

        link.setActive(active);
        linkRepository.save(link);

        // Xóa cache sau khi commit để trạng thái mới có hiệu lực ngay
        registerCacheEvictAfterCommit(link.getShortCode());

        return mapToResponse(link);
    }

    @Transactional
    public LinkResponse toggleActive(Long linkId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại hoặc bạn không có quyền sửa!"));

        if (link.isBanned()) {
            throw new IllegalArgumentException("Link này đã bị Quản trị viên khoá (Banned), không thể tự mở lại!");
        }

        link.setActive(!link.isActive());
        linkRepository.save(link);

        // Xóa cache sau khi commit để trạng thái mới có hiệu lực ngay
        registerCacheEvictAfterCommit(link.getShortCode());

        return mapToResponse(link);
    }

    /**
     * Đăng ký evict cache sau khi Transaction đã commit thành công (afterCommit).
     * Tránh race condition: nếu evict trước commit, request redirect đến giữa chừng
     * sẽ đọc DB cũ (chưa commit) rồi nạp lại dữ liệu cũ vào cache (stale cache).
     */
    private void registerCacheEvictAfterCommit(String... shortCodes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String code : shortCodes) {
                        if (code != null && !code.isBlank()) {
                            linkCacheService.evict(code);
                        }
                    }
                }
            });
        } else {
            for (String code : shortCodes) {
                if (code != null && !code.isBlank()) {
                    linkCacheService.evict(code);
                }
            }
        }
    }
}
