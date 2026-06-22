package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRewardRepository extends JpaRepository<ReferralReward, Long> {
    List<ReferralReward> findAllByOrderBySettledAtDesc();
    List<ReferralReward> findByInviterUserIdOrderBySettledAtDesc(Long inviterUserId);
    Optional<ReferralReward> findFirstByPayOrderId(Long payOrderId);
    boolean existsByPayOrderId(Long payOrderId);
    long countByInviterUserId(Long inviterUserId);
}
