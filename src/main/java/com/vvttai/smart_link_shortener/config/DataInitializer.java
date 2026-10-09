package com.vvttai.smart_link_shortener.config;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Tự động tạo tài khoản Admin mặc định khi khởi động (nếu chưa tồn tại).
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!userRepository.existsUserByUsernameAndDeletedFalse("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRoles("ROLE_ADMIN");
            admin.setEmail("admin@smartlink.local");
            admin.setDisplayName("Administrator");
            userRepository.save(admin);
            log.info("✅ Đã tạo tài khoản Admin mặc định (username: admin / password: admin123)");
        } else {
            log.info("ℹ️ Tài khoản Admin đã tồn tại, bỏ qua khởi tạo.");
        }
    }
}
