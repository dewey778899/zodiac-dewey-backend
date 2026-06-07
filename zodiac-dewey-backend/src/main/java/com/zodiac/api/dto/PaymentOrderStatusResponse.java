package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentOrderStatusResponse {
    private String outTradeNo;
    private String status;
    private boolean paid;
    private String accessToken;
    private String channel;
    private String scene;
    private boolean tokenConsumed;
}
