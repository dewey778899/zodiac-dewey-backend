package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class PaymentOrderResponse {
    private String outTradeNo;
    private String status;
    private String channel;
    private String scene;
    private boolean paid;
    private String accessToken;
    private LocalDateTime expireAt;
    private Map<String, Object> payPayload;
}
