package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralBindingRepository extends JpaRepository<ReferralBinding, Long> {
    Optional<ReferralBinding> findByInviteeUserId(Long inviteeUserId);
    List<ReferralBinding> findAllByOrderByBoundAtDesc();
    long countByInviterUserId(Long inviterUserId);
}
