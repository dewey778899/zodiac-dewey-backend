package com.zodiac.api.repository;

import com.zodiac.api.entity.PremiumUnlockRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PremiumUnlockRequestRepository extends JpaRepository<PremiumUnlockRequest, Long> {
    Optional<PremiumUnlockRequest> findByAccessToken(String accessToken);
    Page<PremiumUnlockRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
