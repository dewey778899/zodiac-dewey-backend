package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReferralBindRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;

    private String inviteCode;

    private String openid;

    private String unionid;

    private String platform;

    private String deviceToken;

    private String source;

    private String displayName;
}
