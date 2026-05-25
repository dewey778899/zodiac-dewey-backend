package com.zodiac.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminOverviewResponse {

    private MetricsBlock generateClick;
    private MetricsBlock generateSuccess;
    private QrMetricsBlock qrModalOpen;
    private QrMetricsBlock qrView;
    private QrMetricsBlock qrSwitch;
    private List<TrendPoint> trends;

    @Data
    @Builder
    public static class MetricsBlock {
        private long total;
        private long today;
        private long deepseekTotal;
        private long deepseekToday;
        private long claudeTotal;
        private long claudeToday;
    }

    @Data
    @Builder
    public static class QrMetricsBlock {
        private long total;
        private long today;
        private long wechatTotal;
        private long wechatToday;
        private long alipayTotal;
        private long alipayToday;
    }

    @Data
    @Builder
    public static class TrendPoint {
        private String date;
        private long deepseekClicks;
        private long claudeClicks;
        private long deepseekSuccess;
        private long claudeSuccess;
        private long qrModalOpens;
        private long wechatViews;
        private long alipayViews;
    }
}
