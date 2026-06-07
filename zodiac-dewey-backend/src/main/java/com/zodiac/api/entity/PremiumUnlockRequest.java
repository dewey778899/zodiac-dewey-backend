package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "premium_unlock_request", indexes = {
        @Index(name = "idx_unlock_access_token", columnList = "access_token", unique = true),
        @Index(name = "idx_unlock_created_at", columnList = "created_at"),
        @Index(name = "idx_unlock_device_token", columnList = "device_token"),
        @Index(name = "idx_unlock_report_type", columnList = "report_type"),
        @Index(name = "idx_unlock_source", columnList = "source")
})
public class PremiumUnlockRequest {

    public static final String SOURCE_DOUYIN_FOLLOW = "douyin_follow";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", length = 50, nullable = false)
    private String source = SOURCE_DOUYIN_FOLLOW;

    @Column(name = "douyin_name", length = 100, nullable = false)
    private String douyinName;

    @Column(name = "confirmed_followed", nullable = false)
    private Boolean confirmedFollowed = Boolean.TRUE;

    @Column(name = "report_type", length = 30, nullable = false)
    private String reportType;

    @Column(name = "access_token", length = 64, nullable = false, unique = true)
    private String accessToken;

    @Column(name = "device_token", length = 128)
    private String deviceToken;

    @Column(name = "client_context", columnDefinition = "TEXT")
    private String clientContext;

    @Column(name = "user_agent", length = 1000)
    private String userAgent;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "token_consumed_at")
    private LocalDateTime tokenConsumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
