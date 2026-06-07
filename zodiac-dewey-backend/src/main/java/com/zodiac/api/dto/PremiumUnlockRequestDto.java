package com.zodiac.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PremiumUnlockRequestDto {

    @NotBlank(message = "抖音名不能为空")
    private String douyinName;

    @AssertTrue(message = "请先确认已关注抖音账号")
    private boolean confirmedFollowed;

    @NotBlank(message = "报告类型不能为空")
    private String reportType;

    private String deviceToken;
    private String clientContext;
    private String userAgent;
}
