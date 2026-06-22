package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referral_reward", indexes = {
        @Index(name = "idx_referral_reward_order", columnList = "pay_order_id"),
        @Index(name = "idx_referral_reward_inviter", columnList = "inviter_user_id"),
        @Index(name = "idx_referral_reward_status", columnList = "status")
})
public class ReferralReward {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pay_order_id", nullable = false)
    private Long payOrderId;

    @Column(name = "inviter_user_id", nullable = false)
    private Long inviterUserId;

    @Column(name = "invitee_user_id", nullable = false)
    private Long inviteeUserId;

    @Column(name = "amount_fen", nullable = false)
    private Integer amountFen;

    @Column(name = "status", length = 32, nullable = false)
    private String status = STATUS_AVAILABLE;

    @Column(name = "withdrawal_id")
    private Long withdrawalId;

    @Column(name = "remark", length = 255)
    private String remark;

    @CreationTimestamp
    @Column(name = "settled_at", nullable = false, updatable = false)
    private LocalDateTime settledAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
