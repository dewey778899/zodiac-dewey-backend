package com.zodiac.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReferralWithdrawRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotNull(message = "提现金额不能为空")
    @Min(value = 1, message = "提现金额必须大于 0")
    private Integer amountFen;

    @NotBlank(message = "提现平台不能为空")
    private String withdrawPlatform;
}
