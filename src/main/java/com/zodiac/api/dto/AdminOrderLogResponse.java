package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminOrderLogResponse {
    private String outTradeNo;
    private List<NotifyLogItem> logs;

    @Data
    @Builder
    public static class NotifyLogItem {
        private Long id;
        private String channel;
        private String notifyType;
        private Boolean verified;
        private String processResult;
        private String errorMessage;
        private String rawPayload;
        private String createdAt;
    }
}
