package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatPhoneBindRequest {

    @NotBlank(message = "微信登录 code 不能为空")
    private String loginCode;

    private String phoneCode;

    private String phoneNumber;

    private String inviteCode;

    private String deviceToken;

    private String source;

    private String displayName;
}
