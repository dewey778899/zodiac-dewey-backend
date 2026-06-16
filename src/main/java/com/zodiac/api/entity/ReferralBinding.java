package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referral_binding", indexes = {
        @Index(name = "idx_ref_binding_inviter", columnList = "inviter_user_id"),
        @Index(name = "idx_ref_binding_invitee", columnList = "invitee_user_id", unique = true),
        @Index(name = "idx_ref_binding_code", columnList = "invite_code")
})
public class ReferralBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inviter_user_id", nullable = false)
    private Long inviterUserId;

    @Column(name = "invitee_user_id", nullable = false, unique = true)
    private Long inviteeUserId;

    @Column(name = "invite_code", length = 32, nullable = false)
    private String inviteCode;

    @Column(name = "bind_source", length = 64)
    private String bindSource;

    @CreationTimestamp
    @Column(name = "bound_at", nullable = false, updatable = false)
    private LocalDateTime boundAt;
}
