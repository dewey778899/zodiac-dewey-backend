package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "soulmate_report", indexes = {
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_ip", columnList = "ip_address"),
        @Index(name = "idx_uid", columnList = "report_uid", unique = true),
        @Index(name = "idx_wechat", columnList = "wechat_id")
})
public class SoulmateReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_uid", length = 50, nullable = false, unique = true)
    private String reportUid;

    @Column(name = "user_a_name", length = 50)
    private String userAName;
    @Column(name = "user_a_gender", length = 10)
    private String userAGender;
    @Column(name = "user_a_birth", length = 20)
    private String userABirthDate;
    @Column(name = "user_a_time", length = 10)
    private String userABirthTime;
    @Column(name = "user_a_place", length = 50)
    private String userABirthPlace;
    @Column(name = "zodiac_a", length = 20)
    private String zodiacA;
    @Column(name = "moon_a", length = 20)
    private String moonA;
    @Column(name = "rising_a", length = 20)
    private String risingA;

    @Column(name = "user_b_name", length = 50)
    private String userBName;
    @Column(name = "user_b_gender", length = 10)
    private String userBGender;
    @Column(name = "user_b_birth", length = 20)
    private String userBBirthDate;
    @Column(name = "user_b_time", length = 10)
    private String userBBirthTime;
    @Column(name = "user_b_place", length = 50)
    private String userBBirthPlace;
    @Column(name = "zodiac_b", length = 20)
    private String zodiacB;
    @Column(name = "moon_b", length = 20)
    private String moonB;
    @Column(name = "rising_b", length = 20)
    private String risingB;

    @Column(name = "score")
    private Integer score;
    @Column(name = "model_code", length = 20)
    private String modelCode;
    @Column(name = "report_type", length = 20)
    private String reportType;
    @Column(name = "relationship_type", length = 50)
    private String relationshipType;
    @Column(name = "tagline", length = 500)
    private String tagline;

    @Column(name = "full_report", columnDefinition = "TEXT")
    private String fullReport;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    @Column(name = "wechat_id", length = 100)
    private String wechatId;

    @Column(name = "shared_count")
    private Integer sharedCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
