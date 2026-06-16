package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referral_user", indexes = {
        @Index(name = "idx_ref_user_phone", columnList = "phone", unique = true),
        @Index(name = "idx_ref_user_invite_code", columnList = "invite_code", unique = true),
        @Index(name = "idx_ref_user_wechat_openid", columnList = "wechat_openid"),
        @Index(name = "idx_ref_user_douyin_openid", columnList = "douyin_openid")
})
public class ReferralUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone", length = 32, nullable = false, unique = true)
    private String phone;

    @Column(name = "wechat_openid", length = 128)
    private String wechatOpenid;

    @Column(name = "douyin_openid", length = 128)
    private String douyinOpenid;

    @Column(name = "unionid", length = 128)
    private String unionid;

    @Column(name = "invite_code", length = 32, nullable = false, unique = true)
    private String inviteCode;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "device_token", length = 128)
    private String deviceToken;

    @Column(name = "balance_fen", nullable = false)
    private Integer balanceFen = 0;

    @Column(name = "withdrawable_fen", nullable = false)
    private Integer withdrawableFen = 0;

    @Column(name = "frozen_fen", nullable = false)
    private Integer frozenFen = 0;

    @Column(name = "withdrawn_fen", nullable = false)
    private Integer withdrawnFen = 0;

    @Column(name = "premium_paid_count", nullable = false)
    private Integer premiumPaidCount = 0;

    @Column(name = "inviter_eligible", nullable = false)
    private Boolean inviterEligible = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
