package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReferralWithdrawalRepository extends JpaRepository<ReferralWithdrawal, Long> {
    List<ReferralWithdrawal> findAllByOrderByCreatedAtDesc();
    List<ReferralWithdrawal> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, LocalDateTime createdAt);
    long countByStatus(String status);
}
