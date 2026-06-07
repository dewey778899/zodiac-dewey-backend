package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "payment_notify_log", indexes = {
        @Index(name = "idx_payment_notify_order_no", columnList = "out_trade_no"),
        @Index(name = "idx_payment_notify_created_at", columnList = "created_at"),
        @Index(name = "idx_payment_notify_channel", columnList = "channel")
})
public class PaymentNotifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "out_trade_no", length = 64)
    private String outTradeNo;

    @Column(name = "channel", length = 20, nullable = false)
    private String channel;

    @Column(name = "notify_type", length = 50)
    private String notifyType;

    @Column(name = "verified")
    private Boolean verified;

    @Column(name = "process_result", length = 50)
    private String processResult;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
