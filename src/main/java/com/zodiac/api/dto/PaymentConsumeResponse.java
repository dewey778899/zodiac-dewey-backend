package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentConsumeResponse {
    private String outTradeNo;
    private boolean success;
    private String status;
    private String message;
}
