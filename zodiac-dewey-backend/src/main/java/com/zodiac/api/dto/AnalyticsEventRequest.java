package com.zodiac.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AnalyticsEventRequest {

    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    private String modelCode;

    private String channel;

    private String reportUid;
}
