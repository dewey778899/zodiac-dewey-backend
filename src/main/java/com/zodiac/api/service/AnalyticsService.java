package com.zodiac.api.service;

import com.zodiac.api.dto.AnalyticsEventRequest;
import com.zodiac.api.entity.AnalyticsEvent;
import com.zodiac.api.repository.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    public static final String EVENT_GENERATE_CLICK = "generate_click";
    public static final String EVENT_GENERATE_SUCCESS = "generate_success";
    public static final String EVENT_QR_MODAL_OPEN = "qr_modal_open";
    public static final String EVENT_QR_VIEW = "qr_view";
    public static final String EVENT_QR_SWITCH = "qr_switch";

    private final AnalyticsEventRepository analyticsEventRepository;

    public void recordFrontendEvent(AnalyticsEventRequest request, String ip, String userAgent) {
        saveEvent(
                request.getEventType(),
                normalizeModelCode(request.getModelCode()),
                normalizeChannel(request.getChannel()),
                request.getReportUid(),
                ip,
                userAgent
        );
    }

    public void recordGenerateSuccess(String modelCode, String reportUid, String ip, String userAgent) {
        saveEvent(EVENT_GENERATE_SUCCESS, normalizeModelCode(modelCode), null, reportUid, ip, userAgent);
    }

    private void saveEvent(String eventType,
                           String modelCode,
                           String channel,
                           String reportUid,
                           String ip,
                           String userAgent) {
        try {
            AnalyticsEvent event = new AnalyticsEvent();
            event.setEventType(eventType);
            event.setModelCode(modelCode);
            event.setChannel(channel);
            event.setReportUid(reportUid);
            event.setIpAddress(trimToLength(ip, 50));
            event.setUserAgent(trimToLength(userAgent, 500));
            analyticsEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Analytics event save failed: type={}, model={}, channel={}, reason={}",
                    eventType, modelCode, channel, e.getMessage());
        }
    }

    private String normalizeModelCode(String modelCode) {
        if ("claude".equalsIgnoreCase(modelCode)) {
            return "claude";
        }
        if ("deepseek".equalsIgnoreCase(modelCode)) {
            return "deepseek";
        }
        return modelCode == null || modelCode.isBlank() ? null : modelCode.trim().toLowerCase();
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String normalized = channel.trim().toLowerCase();
        return ("wechat".equals(normalized) || "alipay".equals(normalized)) ? normalized : null;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
