package com.zodiac.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pay_order", indexes = {
        @Index(name = "idx_pay_out_trade_no", columnList = "out_trade_no", unique = true),
        @Index(name = "idx_pay_created_at", columnList = "created_at"),
        @Index(name = "idx_pay_access_token", columnList = "access_token")
})
public class PayOrder {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String TRADE_TYPE_NATIVE = "NATIVE";
    public static final String TRADE_TYPE_H5 = "H5";
    public static final String TRADE_TYPE_JSAPI = "JSAPI";
    public static final int TOKEN_EXPIRE_HOURS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "out_trade_no", length = 50, nullable = false, unique = true)
    private String outTradeNo;

    @Column(name = "payjs_order_id", length = 50)
    private String payjsOrderId;

    @Column(name = "total_fee")
    private Integer totalFee;

    @Column(name = "amount_fen")
    private Integer amountFen;

    @Column(name = "status", length = 20, nullable = false)
    private String status = STATUS_CREATED;

    @Column(name = "openid", length = 64)
    private String openid;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "channel", length = 20)
    private String channel;

    @Column(name = "scene", length = 50)
    private String scene;

    @Column(name = "scene_code", length = 30)
    private String sceneCode;

    @Column(name = "report_type", length = 30)
    private String reportType;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "return_url", length = 1000)
    private String returnUrl;

    @Column(name = "trade_type", length = 20)
    private String tradeType;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "transaction_id", length = 128)
    private String transactionId;

    @Column(name = "wechat_prepay_id", length = 128)
    private String wechatPrepayId;

    @Column(name = "wechat_mweb_url", length = 1000)
    private String wechatMwebUrl;

    @Column(name = "wechat_code_url", length = 1000)
    private String wechatCodeUrl;

    @Column(name = "alipay_trade_no", length = 128)
    private String alipayTradeNo;

    @Column(name = "alipay_pay_url", length = 2000)
    private String alipayPayUrl;

    @Column(name = "notify_type", length = 32)
    private String notifyType;

    @Column(name = "notify_verified")
    private Boolean notifyVerified;

    @Column(name = "notify_raw", columnDefinition = "TEXT")
    private String notifyRaw;

    @Column(name = "attach_payload", columnDefinition = "TEXT")
    private String attachPayload;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "access_token", length = 64, unique = true)
    private String accessToken;

    @Column(name = "token_consumed_at")
    private LocalDateTime tokenConsumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Integer getAmountFen() {
        return amountFen != null ? amountFen : totalFee;
    }

    public void setAmountFen(Integer amountFen) {
        this.amountFen = amountFen;
        if (this.totalFee == null) {
            this.totalFee = amountFen;
        }
    }

    public String getSceneCode() {
        return sceneCode != null && !sceneCode.isBlank() ? sceneCode : scene;
    }

    public void setSceneCode(String sceneCode) {
        this.sceneCode = sceneCode;
        if ((this.scene == null || this.scene.isBlank()) && sceneCode != null && !sceneCode.isBlank()) {
            this.scene = sceneCode;
        }
    }
}
