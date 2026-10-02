package com.vvttai.smart_link_shortener.modules.link.service;

import com.vvttai.smart_link_shortener.common.exception.LinkExpiredException;
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

    public LinkService(
            LinkRepository linkRepository,
            UserRepository userRepository,
            LinkCacheService linkCacheService,
            UrlValidationService urlValidationService,
            ClickAnalyticsRepository clickAnalyticsRepository,
            AnalyticsService analyticsService) {
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.linkCacheService = linkCacheService;
        this.urlValidationService = urlValidationService;
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.analyticsService = analyticsService;
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

        String fullShortUrl = "http://localhost:8080/r/" + link.getShortCode();
        link.setOriginalUrl(request.originalUrl());
        link.setExpiresAt(request.expiresAt());

        return new LinkResponse(
                link.getId(),
                link.getOriginalUrl(),
                link.getShortCode(),
                fullShortUrl,
                link.getClickCount(),
                link.getCreatedAt(),
                link.getExpiresAt());
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

    public void deleteLink(Long linkId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại"));

        // Xóa cache trước khi delete DB
        linkCacheService.evict(link.getShortCode());
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
        String fullShortUrl = "http://localhost:8080/r/" + link.getShortCode();
        // Luôn dùng click_analytics trong DB làm nguồn sự thật (chính xác 100%)
        long actualClicks = clickAnalyticsRepository.countByLinkId(link.getId());
        return new LinkResponse(
                link.getId(),
                link.getOriginalUrl(),
                link.getShortCode(),
                fullShortUrl,
                actualClicks,
                link.getCreatedAt(),
                link.getExpiresAt());
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
            if (!cached.active()) {
                throw new LinkNotFoundException(shortCode);
            }
            if (cached.expiresAt() != null && LocalDateTime.now().isAfter(cached.expiresAt())) {
                linkCacheService.evict(shortCode); // Xóa cache link hết hạn
                throw new LinkExpiredException(shortCode);
            }
            return cached.toEntity();
        }

        // --- 2. Cache MISS -> Query DB ---
        log.debug("Cache MISS: shortCode={}", shortCode);
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        // Kiểm tra link có bị tắt không
        if (!link.isActive()) {
            throw new LinkNotFoundException(shortCode);
        }

        // Kiểm tra link có hết hạn không
        if (link.getExpiresAt() != null && LocalDateTime.now().isAfter(link.getExpiresAt())) {
            throw new LinkExpiredException(shortCode);
        }

        // --- 3. Put vào cache (TTL = 1 giờ) ---
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
     * Khác với getLinkByShortCode() dùng khi redirect, phương thức này KHÔNG ném LinkExpiredException
     * khi link đã hết hạn, cho phép người dùng vẫn xem được toàn bộ dữ liệu thống kê của link.
     */
    public Link getLinkForStats(String shortCode) {
        // --- 1. Check Redis cache ---
        LinkCacheDto cached = linkCacheService.get(shortCode);
        if (cached != null) {
            log.debug("Cache HIT for stats: shortCode={}", shortCode);
            if (!cached.active()) {
                throw new LinkNotFoundException(shortCode);
            }
            return cached.toEntity();
        }

        // --- 2. Cache MISS -> Query DB ---
        log.debug("Cache MISS for stats: shortCode={}", shortCode);
        Link link = linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkNotFoundException(shortCode));

        if (!link.isActive()) {
            throw new LinkNotFoundException(shortCode);
        }

        return link;
    }

    @Transactional
    public LinkResponse setExpirationDate(Long linkId, LocalDateTime expiresAt, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        Link link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new RuntimeException("Link không tồn tại"));

        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            throw new IllegalArgumentException("Thời gian hết hạn phải ở trong tương lai!");
        }
        link.setExpiresAt(expiresAt);
        linkRepository.save(link);

        // Xóa cache vì TTL đã thay đổi
        linkCacheService.evict(link.getShortCode());

        return mapToResponse(link);
    }
}
