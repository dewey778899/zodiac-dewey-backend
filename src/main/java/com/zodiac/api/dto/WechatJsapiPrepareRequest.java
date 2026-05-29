package com.zodiac.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatJsapiPrepareRequest {

    @NotBlank(message = "微信授权 code 不能为空")
    private String code;

    @Valid
    private PaymentCreateOrderRequest order;
}
