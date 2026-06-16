package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralReward;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRewardRepository extends JpaRepository<ReferralReward, Long> {
    Optional<ReferralReward> findByPayOrderId(Long payOrderId);
    List<ReferralReward> findByInviterUserIdOrderBySettledAtDesc(Long inviterUserId);
    List<ReferralReward> findByWithdrawalId(Long withdrawalId);
    Page<ReferralReward> findAllByOrderBySettledAtDesc(Pageable pageable);
    long countByInviterUserId(Long inviterUserId);
}
