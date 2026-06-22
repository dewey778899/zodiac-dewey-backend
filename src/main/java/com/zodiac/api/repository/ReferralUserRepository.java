package com.zodiac.api.repository;

import com.zodiac.api.entity.ReferralUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralUserRepository extends JpaRepository<ReferralUser, Long> {
    Optional<ReferralUser> findByPhone(String phone);
    Optional<ReferralUser> findByInviteCode(String inviteCode);
    Optional<ReferralUser> findByWechatOpenid(String wechatOpenid);
    List<ReferralUser> findAllByOrderByCreatedAtDesc();
}
