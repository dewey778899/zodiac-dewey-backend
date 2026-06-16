package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referral_reward", indexes = {
        @Index(name = "idx_ref_reward_inviter", columnList = "inviter_user_id"),
        @Index(name = "idx_ref_reward_invitee", columnList = "invitee_user_id"),
        @Index(name = "idx_ref_reward_status", columnList = "status"),
        @Index(name = "idx_ref_reward_pay_order", columnList = "pay_order_id", unique = true)
})
public class ReferralReward {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_WITHDRAW_APPLIED = "WITHDRAW_APPLIED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pay_order_id", nullable = false, unique = true)
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

    @CreationTimestamp
    @Column(name = "settled_at", nullable = false, updatable = false)
    private LocalDateTime settledAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
