package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralWithdrawal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReferralWithdrawalRepository extends JpaRepository<ReferralWithdrawal, Long> {
    List<ReferralWithdrawal> findByUserIdOrderByCreatedAtDesc(Long userId);
    Page<ReferralWithdrawal> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
