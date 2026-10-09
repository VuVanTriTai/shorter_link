package com.vvttai.smart_link_shortener.modules.user.repository;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // ========== Auth queries — chỉ trả user chưa bị soft-delete ==========

    /**
     * Lookup chính dùng trong đăng nhập, refresh token, change-password.
     * Chỉ trả user chưa bị soft-delete.
     */
    Optional<User> findByUsernameAndDeletedFalse(String username);

    /**
     * Dùng trong loginWithGoogle để liên kết/tìm user theo email.
     * Chỉ trả user chưa bị soft-delete.
     */
    Optional<User> findByEmailAndDeletedFalse(String email);

    /**
     * Dùng trong loginWithGoogle để tìm user đã liên kết Google trước đó.
     * Chỉ trả user chưa bị soft-delete.
     */
    Optional<User> findByAuthProviderAndProviderIdAndDeletedFalse(String authProvider, String providerId);

    /** Kiểm tra username đã tồn tại (chỉ xét user chưa xoá để cho phép tái sử dụng username sau soft-delete). */
    boolean existsUserByUsernameAndDeletedFalse(String username);

    // ========== Legacy — giữ lại cho backward compat, KHÔNG dùng trong auth flow ==========

    /** @deprecated Dùng {@link #findByUsernameAndDeletedFalse} thay thế */
    @Deprecated
    Optional<User> findByUsername(String username);

    /** @deprecated Dùng {@link #findByEmailAndDeletedFalse} thay thế */
    @Deprecated
    Optional<User> findByEmail(String email);

    /** @deprecated Dùng {@link #findByAuthProviderAndProviderIdAndDeletedFalse} thay thế */
    @Deprecated
    Optional<User> findByAuthProviderAndProviderId(String authProvider, String providerId);

    // ========== Admin queries ==========

    /** Danh sách user chưa bị xoá (phân trang) */
    Page<User> findByDeletedFalse(Pageable pageable);

    /** Tìm kiếm user theo username (phân trang, chưa bị xoá) */
    Page<User> findByUsernameContainingIgnoreCaseAndDeletedFalse(String username, Pageable pageable);

    /** Tổng số user chưa bị xoá */
    long countByDeletedFalse();

    /** Số user mới đăng ký sau ngày chỉ định */
    long countByCreatedAtAfterAndDeletedFalse(LocalDateTime date);

    /** Số user mới đăng ký trong khoảng thời gian */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt BETWEEN :from AND :to AND u.deleted = false")
    long countNewUsersInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
