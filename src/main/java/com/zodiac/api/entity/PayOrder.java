package com.zodiac.api.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pay_order", indexes = {
        @Index(name = "idx_pay_out_trade_no", columnList = "out_trade_no", unique = true),
        @Index(name = "idx_pay_created_at", columnList = "created_at"),
        @Index(name = "idx_pay_access_token", columnList = "access_token"),
        @Index(name = "idx_pay_status", columnList = "status"),
        @Index(name = "idx_pay_channel", columnList = "channel"),
        @Index(name = "idx_pay_device_token", columnList = "device_token"),
        @Index(name = "idx_pay_report_uid", columnList = "report_uid")
})
public class PayOrder {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REFUND_PENDING = "REFUND_PENDING";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_ALIPAY = "ALIPAY";

    public static final String TRADE_TYPE_JSAPI = "JSAPI";
    public static final String TRADE_TYPE_H5 = "H5";
    public static final String TRADE_TYPE_NATIVE = "NATIVE";
    public static final String TRADE_TYPE_WAP = "WAP";

    public static final int TOKEN_EXPIRE_HOURS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "out_trade_no", length = 64, nullable = false, unique = true)
    private String outTradeNo;

    @Column(name = "channel", length = 20)
    private String channel;

    @Column(name = "trade_type", length = 20)
    private String tradeType;

    @Column(name = "status", length = 30, nullable = false)
    private String status = STATUS_CREATED;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "amount_fen")
    private Integer amountFen;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "openid", length = 128)
    private String openid;

    @Column(name = "wechat_prepay_id", length = 128)
    private String wechatPrepayId;

    @Column(name = "wechat_mweb_url", length = 1000)
    private String wechatMwebUrl;

    @Column(name = "wechat_code_url", length = 1000)
    private String wechatCodeUrl;

    @Column(name = "wechat_transaction_id", length = 128)
    private String wechatTransactionId;

    @Column(name = "alipay_trade_no", length = 128)
    private String alipayTradeNo;

    @Column(name = "alipay_form_html", columnDefinition = "TEXT")
    private String alipayFormHtml;

    @Column(name = "notify_raw", columnDefinition = "TEXT")
    private String notifyRaw;

    @Column(name = "notify_verified")
    private Boolean notifyVerified;

    @Column(name = "notify_channel", length = 20)
    private String notifyChannel;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;

    @Column(name = "report_type", length = 30)
    private String reportType;

    @Column(name = "report_uid", length = 64)
    private String reportUid;

    @Column(name = "device_token", length = 128)
    private String deviceToken;

    @Column(name = "scene_code", length = 30)
    private String sceneCode;

    @Column(name = "wechat_auth_code", length = 256)
    private String wechatAuthCode;

    @Column(name = "return_url", length = 1000)
    private String returnUrl;

    @Column(name = "attach_payload", columnDefinition = "TEXT")
    private String attachPayload;

    @Column(name = "access_token", length = 64, unique = true)
    private String accessToken;

    @Column(name = "token_consumed_at")
    private LocalDateTime tokenConsumedAt;

    @Column(name = "last_status_sync_at")
    private LocalDateTime lastStatusSyncAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
