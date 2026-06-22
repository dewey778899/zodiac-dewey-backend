package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReferralVisitRequest {
    @NotBlank(message = "邀请码不能为空")
    private String inviteCode;

    private String deviceToken;

    private String source;
}
