package com.vvttai.smart_link_shortener.modules.link.repository;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Link> findByUserUsername(String username);
    Optional<Link> findByIdAndUserUsername(Long id, String username);
    Optional<Link> findByIdAndUserId(Long id, Long userId);
    Optional<Link> findByShortCodeAndUserId(String shortCode, Long userId);
    Optional<Link> findByShortCodeAndUserUsername(String shortCode, String username);
}
