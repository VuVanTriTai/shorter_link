package com.vvttai.smart_link_shortener.modules.link.repository;

import com.vvttai.smart_link_shortener.modules.link.entity.Link;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Link> findByUserUsername(String username);
    Optional<Link> findByIdAndUserId(Long id, Long userId);
    Optional<Link> findByShortCodeAndUserId(String shortCode, Long userId);
    Optional<Link> findByShortCodeAndUserUsername(String shortCode, String username);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE Link l SET l.clickCount = :total WHERE l.id = :id")
    void syncClickCount(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("total") long total);

    /**
     * Sync click_count cho nhiều link trong 1 SQL duy nhất.
     * SET click_count = (SELECT COUNT(*) FROM click_analytics WHERE link_id = links.id)
     * → O(1) query thay vì O(N) SELECT + O(N) UPDATE
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
        value = "UPDATE links SET click_count = (SELECT COUNT(*) FROM click_analytics WHERE link_id = links.id) WHERE id IN (:linkIds)",
        nativeQuery = true
    )
    void syncClickCountBatch(@org.springframework.data.repository.query.Param("linkIds") java.util.Set<Long> linkIds);
}
