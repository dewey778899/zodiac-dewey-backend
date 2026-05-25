package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "analytics_event", indexes = {
        @Index(name = "idx_event_created_at", columnList = "created_at"),
        @Index(name = "idx_event_type", columnList = "event_type"),
        @Index(name = "idx_event_model", columnList = "model_code"),
        @Index(name = "idx_event_channel", columnList = "channel")
})
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "model_code", length = 20)
    private String modelCode;

    @Column(name = "channel", length = 20)
    private String channel;

    @Column(name = "report_uid", length = 50)
    private String reportUid;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
