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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    private final LinkRepository linkRepository;
    private final UserRepository userRepository;
    private final LinkCacheService linkCacheService;
    private final UrlValidationService urlValidationService;
    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final AnalyticsService analyticsService;
    private final PasswordEncoder passwordEncoder;

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

    public LinkResponse createShortLink(CreateLinkRequest request, Principal connectedUser) {
        // 1. Kiểm tra an toàn và xác thực URL đích (Scheme, Self-loop, SSRF,
        // Reachability, Safe Browsing)
        urlValidationService.validateUrl(request.originalUrl());

        User user = userRepository.findByUsername(connectedUser.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + connectedUser.getName()));
        String shortCode;
        if (request.customCode() != null && !request.customCode().isBlank()) {
            String customCode = request.customCode().trim();
            // 2. Kiểm tra tính hợp lệ của mã custom (ký tự, độ dài, blacklist)
            urlValidationService.validateCustomCode(customCode);

            if (linkRepository.existsByShortCode(customCode)) {
                throw new IllegalArgumentException("Mã custom short code này đã tồn tại!");
            }
            shortCode = customCode;
        } else {
            shortCode = generateUniqueShortCode();
        }
        Link link = new Link(request.originalUrl(), shortCode, user);
        // Bug fix: expiresAt từ request trước đây bị bỏ qua hoàn toàn
        if (request.expiresAt() != null) {
            if (LocalDateTime.now().isAfter(request.expiresAt())) {
                throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
            }
            link.setExpiresAt(request.expiresAt());
        }
        if (request.password() != null && !request.password().trim().isEmpty()) {
            link.setPassword(passwordEncoder.encode(request.password().trim()));
        }
        linkRepository.save(link);
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

        // Xóa cache cũ trước khi update (shortCode cũ có thể bị đổi)
        linkCacheService.evict(currentShortCode);

        // Nếu người dùng muốn đổi sang customCode mới
        if (request.customCode() != null && !request.customCode().isBlank()) {
            String newCode = request.customCode().trim();
            // 2. Kiểm tra tính hợp lệ của mã custom mới
            urlValidationService.validateCustomCode(newCode);

            if (!newCode.equals(link.getShortCode()) && linkRepository.existsByShortCode(newCode)) {
                throw new IllegalArgumentException("Mã custom short code này đã tồn tại!");
            }
            link.setShortCode(newCode);
        }

        String fullShortUrl = "/r/" + link.getShortCode();
        link.setOriginalUrl(request.originalUrl());
        link.setExpiresAt(request.expiresAt());
        if (request.active() != null) {
            link.setActive(request.active());
        }

        // Cập nhật mật khẩu bảo vệ
        if (Boolean.TRUE.equals(request.removePassword())) {
            link.setPassword(null);
        } else if (request.password() != null && !request.password().trim().isEmpty()) {
            link.setPassword(passwordEncoder.encode(request.password().trim()));
        }

        return mapToResponse(link);
    }

    public List<LinkResponse> getUserLinks(Principal connectedUser) {
        // Đẩy ngay các click đang chờ trong Redis buffer vào DB trước khi lấy danh sách
        try {
            analyticsService.flushClickBuffer();
        } catch (Exception e) {
            log.warn("Failed to flush click buffer before fetching user links: {}", e.getMessage());
        }

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

        // Xóa cache trước khi delete DB
        linkCacheService.evict(link.getShortCode());
        clickAnalyticsRepository.deleteByLinkId(link.getId());
        linkRepository.delete(link);
    }

    private String generateUniqueShortCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (linkRepository.existsByShortCode(code));
        return code;
    }

    private LinkResponse mapToResponse(Link link) {
        String fullShortUrl = "/r/" + link.getShortCode();
        // Fix N+1: dùng cột click_count đã được đồng bộ (syncClickCountBatch mỗi 5s)
        // thay vì gọi COUNT(*) riêng cho từng link → tránh 100 query cho 100 link
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
            // Validate trạng thái từ cache
            if (cached.banned() || !cached.active()) {
                throw new LinkInactiveException(shortCode);
            }
            if (cached.expiresAt() != null && LocalDateTime.now().isAfter(cached.expiresAt())) {
                linkCacheService.evict(shortCode); // Xóa cache link hết hạn
                throw new LinkExpiredException(shortCode);
            }
            // Link từ cache có password=null (hash không được cache)
            // Dùng hasPassword flag từ cache để biết link có cần mật khẩu không
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
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        // Kiểm tra link có bị khoá/tắt không
        if (link.isBanned() || !link.isActive()) {
            // Cache trạng thái để chống spam DB
            linkCacheService.put(link);
            throw new LinkInactiveException(shortCode);
        }

        // Kiểm tra link có hết hạn không
        if (link.getExpiresAt() != null && LocalDateTime.now().isAfter(link.getExpiresAt())) {
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
            log.debug("Cache HIT for stats: shortCode={}", shortCode);
            return cached.toEntity();
        }

        // --- 2. Cache MISS -> Query DB ---
        log.debug("Cache MISS for stats: shortCode={}", shortCode);
        return linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));
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
    public LinkResponse setExpirationDate(Long linkId, LocalDateTime expiresAt, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại"));

        if (link.isBanned()) {
            throw new IllegalArgumentException("Link này đã bị Quản trị viên khoá, không thể chỉnh sửa!");
        }

        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
        }
        link.setExpiresAt(expiresAt);
        linkRepository.save(link);

        // Xóa cache vì TTL đã thay đổi
        linkCacheService.evict(link.getShortCode());

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

        // Xóa cache trong Redis để trạng thái mới có hiệu lực ngay
        linkCacheService.evict(link.getShortCode());

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

        // Xóa cache trong Redis để trạng thái mới có hiệu lực ngay
        linkCacheService.evict(link.getShortCode());

        return mapToResponse(link);
    }
}
