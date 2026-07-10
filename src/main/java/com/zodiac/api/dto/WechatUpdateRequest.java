package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatUpdateRequest {
    @NotBlank(message = "内容编号不能为空")
    private String reportUid;

    @NotBlank(message = "微信号不能为空")
    private String wechatId;
}
