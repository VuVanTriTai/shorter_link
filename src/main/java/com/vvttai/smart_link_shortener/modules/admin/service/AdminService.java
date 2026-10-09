package com.vvttai.smart_link_shortener.modules.admin.service;

import com.vvttai.smart_link_shortener.modules.admin.dto.AdminDashboardResponse;
import com.vvttai.smart_link_shortener.modules.admin.dto.AdminLinkResponse;
import com.vvttai.smart_link_shortener.modules.admin.dto.AdminUserResponse;
import com.vvttai.smart_link_shortener.modules.analytics.repository.ClickAnalyticsRepository;
import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import com.vvttai.smart_link_shortener.modules.link.repository.LinkRepository;
import com.vvttai.smart_link_shortener.modules.link.service.LinkCacheService;
import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final LinkRepository linkRepository;
    private final ClickAnalyticsRepository clickAnalyticsRepository;
    private final LinkCacheService linkCacheService;

    public AdminService(
            UserRepository userRepository,
            LinkRepository linkRepository,
            ClickAnalyticsRepository clickAnalyticsRepository,
            LinkCacheService linkCacheService) {
        this.userRepository = userRepository;
        this.linkRepository = linkRepository;
        this.clickAnalyticsRepository = clickAnalyticsRepository;
        this.linkCacheService = linkCacheService;
    }

    // ======================== Dashboard ========================

    public AdminDashboardResponse getDashboardStats() {
        long totalUsers = userRepository.countByDeletedFalse();
        long totalLinks = linkRepository.count();
        long totalClicks = clickAnalyticsRepository.count();

        // User mới hôm nay
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long newUsersToday = userRepository.countByCreatedAtAfterAndDeletedFalse(startOfToday);

        // User mới tuần này (bắt đầu từ thứ Hai)
        LocalDate startOfWeek = LocalDate.now().with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
        long newUsersThisWeek = userRepository.countByCreatedAtAfterAndDeletedFalse(startOfWeek.atStartOfDay());

        // Top trending links (đang hoạt động và không bị khoá)
        List<Link> topLinks = linkRepository.findTop10ByActiveTrueAndBannedFalseOrderByClickCountDesc();
        List<AdminDashboardResponse.TrendingLink> trendingLinks = topLinks.stream()
                .map(link -> new AdminDashboardResponse.TrendingLink(
                        link.getId(),
                        link.getShortCode(),
                        link.getOriginalUrl(),
                        link.getClickCount(),
                        link.getUserName()))
                .toList();

        return new AdminDashboardResponse(
                totalUsers, totalLinks, totalClicks,
                newUsersToday, newUsersThisWeek,
                trendingLinks);
    }

    // ======================== User Management ========================

    /**
     * Danh sách user (phân trang + tìm kiếm theo username).
     */
    public Page<AdminUserResponse> getUsers(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<User> users;

        if (search != null && !search.isBlank()) {
            users = userRepository.findByUsernameContainingIgnoreCaseAndDeletedFalse(search.trim(), pageable);
        } else {
            users = userRepository.findByDeletedFalse(pageable);
        }

        return users.map(this::mapUserToResponse);
    }

    /**
     * Chi tiết 1 user.
     */
    public AdminUserResponse getUserDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User không tồn tại: " + userId));
        return mapUserToResponse(user);
    }

    /**
     * Khoá/mở khoá tài khoản.
     */
    @Transactional
    public AdminUserResponse toggleLock(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User không tồn tại: " + userId));

        // Không cho phép khoá chính tài khoản admin
        if ("ROLE_ADMIN".equals(user.getRoles())) {
            throw new IllegalArgumentException("Không thể khoá tài khoản Admin!");
        }

        user.setLocked(!user.isLocked());
        userRepository.save(user);
        log.info("Admin đã {} tài khoản: {}", user.isLocked() ? "KHOÁ" : "MỞ KHOÁ", user.getUsername());
        return mapUserToResponse(user);
    }

    /**
     * Soft-delete tài khoản (giữ lại data link cũ).
     */
    @Transactional
    public void softDeleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User không tồn tại: " + userId));

        if ("ROLE_ADMIN".equals(user.getRoles())) {
            throw new IllegalArgumentException("Không thể xoá tài khoản Admin!");
        }

        user.setDeleted(true);
        user.setLocked(true); // Khoá luôn để ngăn đăng nhập
        userRepository.save(user);
        log.info("Admin đã soft-delete tài khoản: {}", user.getUsername());
    }

    // ======================== Link Management ========================

    /**
     * Xem toàn bộ link (phân trang + tìm kiếm + lọc theo userId).
     */
    public Page<AdminLinkResponse> getLinks(int page, int size, String search, Long userId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Link> links;

        if (userId != null) {
            links = linkRepository.findByUserId(userId, pageable);
        } else if (search != null && !search.isBlank()) {
            links = linkRepository.searchByKeyword(search.trim(), pageable);
        } else {
            links = linkRepository.findAll(pageable);
        }

        return links.map(this::mapLinkToResponse);
    }

    /**
     * Khoá/mở khoá link vi phạm (Ban/Unban bởi Admin).
     */
    @Transactional
    public AdminLinkResponse toggleLinkActive(Long linkId) {
        Link link = linkRepository.findById(linkId)
                .orElseThrow(() -> new RuntimeException("Link không tồn tại: " + linkId));

        boolean newBanned = !link.isBanned();
        link.setBanned(newBanned);
        if (newBanned) {
            link.setActive(false);
        } else {
            link.setActive(true);
        }
        linkRepository.save(link);

        // Xoá cache để trạng thái mới có hiệu lực ngay
        linkCacheService.evict(link.getShortCode());

        log.info("Admin đã {} link: {}", newBanned ? "KHOÁ VI PHẠM (BAN)" : "MỞ KHOÁ (UNBAN)", link.getShortCode());
        return mapLinkToResponse(link);
    }

    /**
     * Xoá link (hard delete) - xoá cả click_analytics liên quan.
     */
    @Transactional
    public void deleteLink(Long linkId) {
        Link link = linkRepository.findById(linkId)
                .orElseThrow(() -> new RuntimeException("Link không tồn tại: " + linkId));

        linkCacheService.evict(link.getShortCode());
        clickAnalyticsRepository.deleteByLinkId(link.getId());
        linkRepository.delete(link);
        log.info("Admin đã xoá link: {}", link.getShortCode());
    }

    // ======================== Mapping Helpers ========================

    private AdminUserResponse mapUserToResponse(User user) {
        long linkCount = linkRepository.findByUserUsername(user.getUsername()).size();
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getRoles(),
                user.getAuthProvider(),
                user.isLocked(),
                user.isDeleted(),
                user.getCreatedAt(),
                linkCount);
    }

    private AdminLinkResponse mapLinkToResponse(Link link) {
        return new AdminLinkResponse(
                link.getId(),
                link.getOriginalUrl(),
                link.getShortCode(),
                "/r/" + link.getShortCode(),
                link.getClickCount(),
                link.isActive(),
                link.isBanned(),
                link.hasPassword(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.getUser() != null ? link.getUser().getId() : null,
                link.getUserName());
    }
}
