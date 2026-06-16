package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referral_withdrawal", indexes = {
        @Index(name = "idx_ref_withdraw_user", columnList = "user_id"),
        @Index(name = "idx_ref_withdraw_status", columnList = "status")
})
public class ReferralWithdrawal {

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount_fen", nullable = false)
    private Integer amountFen;

    @Column(name = "status", length = 32, nullable = false)
    private String status = STATUS_APPLIED;

    @Column(name = "payee_account_snapshot", columnDefinition = "TEXT")
    private String payeeAccountSnapshot;

    @Column(name = "withdraw_platform", length = 32)
    private String withdrawPlatform;

    @Column(name = "remark", length = 1000)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
