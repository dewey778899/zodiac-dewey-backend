package com.zodiac.api.entity;

import jakarta.persistence.*;
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
    /** token 有效期（小时） */
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

    @Column(name = "status", length = 20, nullable = false)
    private String status = STATUS_CREATED;

    @Column(name = "openid", length = 64)
    private String openid;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * 支付成功后生成的一次性访问 Token。
     * 前端凭此 token 调用 /api/compatibility (claude 版) 而无需再走支付。
     * 有效期 TOKEN_EXPIRE_HOURS 小时，使用后即消耗（tokenConsumedAt 非 null）。
     */
    @Column(name = "access_token", length = 64, unique = true)
    private String accessToken;

    /** token 被消耗的时间（非 null 表示已使用） */
    @Column(name = "token_consumed_at")
    private LocalDateTime tokenConsumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
