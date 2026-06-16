package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralBindingRepository extends JpaRepository<ReferralBinding, Long> {
    Optional<ReferralBinding> findByInviteeUserId(Long inviteeUserId);
    List<ReferralBinding> findByInviterUserIdOrderByBoundAtDesc(Long inviterUserId);
    Page<ReferralBinding> findAllByOrderByBoundAtDesc(Pageable pageable);
    long countByInviterUserId(Long inviterUserId);
}
