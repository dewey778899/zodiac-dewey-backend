package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PaymentCreateOrderRequest {

    @NotBlank(message = "支付渠道不能为空")
    private String channel;

    @NotBlank(message = "支付场景不能为空")
    private String scene;

    @NotBlank(message = "报告类型不能为空")
    private String reportType;

    @NotNull(message = "支付金额不能为空")
    private Integer amountFen;

    private String openid;

    private String returnUrl;

    private String subject;

    private String outTradeNo;

    private ClientContext clientContext;

    @Data
    public static class ClientContext {
        private String deviceToken;
        private String userAgent;
        private String source;
        private Boolean insideWechat;
        private Boolean mobile;
    }
}
