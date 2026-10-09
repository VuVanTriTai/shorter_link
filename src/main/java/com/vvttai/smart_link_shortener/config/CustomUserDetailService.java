package com.vvttai.smart_link_shortener.config;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Tra cứu thông tin user từ DB cho Spring Security.
 * <p>
 * Các điều kiện bắt buộc để đăng nhập được:
 * <ol>
 *   <li>User phải tồn tại và {@code deleted = false}.</li>
 *   <li>User không bị khoá ({@code locked = false}).</li>
 * </ol>
 * OAuth user (Google) có {@code password = null} — được xử lý bằng placeholder
 * để Spring Security không từ chối tài khoản nhưng vẫn không khớp bất kỳ hash nào.
 */
@Service
public class CustomUserDetailService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Dùng query soft-delete-aware — không trả user đã bị xoá
        User user = userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new UsernameNotFoundException("Tài khoản không tồn tại: " + username));

        // Tài khoản bị khoá bởi Admin — Spring Security sẽ ném LockedException
        // khi isAccountNonLocked() trả false, nhưng check tường minh ở đây để log rõ hơn
        if (user.isLocked()) {
            throw new LockedException("Tài khoản đã bị khoá bởi quản trị viên: " + username);
        }

        // Chuẩn hoá role: đảm bảo luôn có prefix ROLE_
        String role = (user.getRoles() != null && !user.getRoles().isBlank()) ? user.getRoles() : "user";
        if (!role.startsWith("ROLE_")) {
            role = "ROLE_" + role.toUpperCase();
        }

        // OAuth user (password == null) — dùng placeholder; sẽ không khớp bất kỳ real hash nào
        String password = (user.getPassword() != null && !user.getPassword().isBlank())
                ? user.getPassword()
                : "{noop}OAUTH_USER_NO_PASSWORD";

        // Truyền đủ 4 boolean: enabled=true, accountNonExpired=true,
        // credentialsNonExpired=true, accountNonLocked=!locked
        // (locked đã được check ở trên nhưng đặt đúng flag để các filter khác hoạt động đúng)
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                password,
                /* enabled             */ true,
                /* accountNonExpired   */ true,
                /* credentialsNonExpired */ true,
                /* accountNonLocked    */ !user.isLocked(),
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );
    }
}
