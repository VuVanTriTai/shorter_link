package com.vvttai.smart_link_shortener.modules.user.repository;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
    boolean existsUserByUsername(String username);

    Optional<User> findByEmail(String email);
    Optional<User> findByAuthProviderAndProviderId(String authProvider, String providerId);
}
