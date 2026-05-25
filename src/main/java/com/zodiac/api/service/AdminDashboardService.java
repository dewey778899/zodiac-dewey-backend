package com.zodiac.api.service;

import com.zodiac.api.dto.AdminOverviewResponse;
import com.zodiac.api.dto.AdminReportPageResponse;
import com.zodiac.api.entity.SoulmateReport;
import com.zodiac.api.repository.AnalyticsEventRepository;
import com.zodiac.api.repository.SoulmateReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final DateTimeFormatter TREND_DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SoulmateReportRepository reportRepository;
    private final AnalyticsEventRepository analyticsEventRepository;

    public AdminOverviewResponse getOverview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        AdminOverviewResponse.MetricsBlock generateClick = AdminOverviewResponse.MetricsBlock.builder()
                .total(eventCount(AnalyticsService.EVENT_GENERATE_CLICK))
                .today(eventCountToday(AnalyticsService.EVENT_GENERATE_CLICK, todayStart))
                .deepseekTotal(eventCountByModel(AnalyticsService.EVENT_GENERATE_CLICK, "deepseek"))
                .deepseekToday(eventCountByModelToday(AnalyticsService.EVENT_GENERATE_CLICK, "deepseek", todayStart))
                .claudeTotal(eventCountByModel(AnalyticsService.EVENT_GENERATE_CLICK, "claude"))
                .claudeToday(eventCountByModelToday(AnalyticsService.EVENT_GENERATE_CLICK, "claude", todayStart))
                .build();

        AdminOverviewResponse.MetricsBlock generateSuccess = AdminOverviewResponse.MetricsBlock.builder()
                .total(reportRepository.count())
                .today(reportRepository.countTodayReports(todayStart))
                .deepseekTotal(reportRepository.countByModelCode("deepseek"))
                .deepseekToday(reportRepository.countByModelCodeAndCreatedAtGreaterThanEqual("deepseek", todayStart))
                .claudeTotal(reportRepository.countByModelCode("claude"))
                .claudeToday(reportRepository.countByModelCodeAndCreatedAtGreaterThanEqual("claude", todayStart))
                .build();

        AdminOverviewResponse.QrMetricsBlock qrModalOpen = buildQrBlock(AnalyticsService.EVENT_QR_MODAL_OPEN, todayStart);
        AdminOverviewResponse.QrMetricsBlock qrView = buildQrBlock(AnalyticsService.EVENT_QR_VIEW, todayStart);
        AdminOverviewResponse.QrMetricsBlock qrSwitch = buildQrBlock(AnalyticsService.EVENT_QR_SWITCH, todayStart);

        return AdminOverviewResponse.builder()
                .generateClick(generateClick)
                .generateSuccess(generateSuccess)
                .qrModalOpen(qrModalOpen)
                .qrView(qrView)
                .qrSwitch(qrSwitch)
                .trends(buildTrends(7))
                .build();
    }

    public AdminReportPageResponse getReports(String query, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var result = reportRepository.searchReports(query, PageRequest.of(safePage, safeSize));

        List<AdminReportPageResponse.ReportItem> items = result.getContent().stream()
                .map(this::toReportItem)
                .toList();

        return AdminReportPageResponse.builder()
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .page(result.getNumber())
                .size(result.getSize())
                .items(items)
                .build();
    }

    private AdminReportPageResponse.ReportItem toReportItem(SoulmateReport report) {
        return AdminReportPageResponse.ReportItem.builder()
                .reportUid(report.getReportUid())
                .userAName(report.getUserAName())
                .userBName(report.getUserBName())
                .modelCode(report.getModelCode())
                .score(report.getScore())
                .relationshipType(report.getRelationshipType())
                .wechatId(report.getWechatId())
                .createdAt(report.getCreatedAt() == null ? "" : report.getCreatedAt().format(REPORT_DATE))
                .build();
    }

    private AdminOverviewResponse.QrMetricsBlock buildQrBlock(String eventType, LocalDateTime todayStart) {
        return AdminOverviewResponse.QrMetricsBlock.builder()
                .total(eventCount(eventType))
                .today(eventCountToday(eventType, todayStart))
                .wechatTotal(eventCountByChannel(eventType, "wechat"))
                .wechatToday(eventCountByChannelToday(eventType, "wechat", todayStart))
                .alipayTotal(eventCountByChannel(eventType, "alipay"))
                .alipayToday(eventCountByChannelToday(eventType, "alipay", todayStart))
                .build();
    }

    private List<AdminOverviewResponse.TrendPoint> buildTrends(int days) {
        LocalDate startDate = LocalDate.now().minusDays(days - 1L);
        LocalDateTime start = startDate.atStartOfDay();

        Map<LocalDate, TrendAccumulator> bucket = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            bucket.put(startDate.plusDays(i), new TrendAccumulator());
        }

        for (Object[] row : analyticsEventRepository.findFieldsForTrendSince(start)) {
            LocalDate day = ((java.time.LocalDateTime) row[0]).toLocalDate();
            TrendAccumulator acc = bucket.get(day);
            if (acc == null) continue;
            String eventType = (String) row[1];
            String modelCode = (String) row[2];
            String channel   = (String) row[3];
            if (AnalyticsService.EVENT_GENERATE_CLICK.equals(eventType)) {
                if ("claude".equals(modelCode))        acc.claudeClicks++;
                else if ("deepseek".equals(modelCode)) acc.deepseekClicks++;
            } else if (AnalyticsService.EVENT_QR_MODAL_OPEN.equals(eventType)) {
                acc.qrModalOpens++;
            } else if (AnalyticsService.EVENT_QR_VIEW.equals(eventType)) {
                if ("alipay".equals(channel))      acc.alipayViews++;
                else if ("wechat".equals(channel)) acc.wechatViews++;
            }
        }

        for (Object[] row : reportRepository.findCreatedAtAndModelSince(start)) {
            LocalDate day = ((java.time.LocalDateTime) row[0]).toLocalDate();
            TrendAccumulator acc = bucket.get(day);
            if (acc == null) continue;
            if ("claude".equals(row[1])) acc.claudeSuccess++;
            else                          acc.deepseekSuccess++;
        }

        List<AdminOverviewResponse.TrendPoint> points = new ArrayList<>();
        bucket.forEach((date, acc) -> points.add(AdminOverviewResponse.TrendPoint.builder()
                .date(date.format(TREND_DATE))
                .deepseekClicks(acc.deepseekClicks)
                .claudeClicks(acc.claudeClicks)
                .deepseekSuccess(acc.deepseekSuccess)
                .claudeSuccess(acc.claudeSuccess)
                .qrModalOpens(acc.qrModalOpens)
                .wechatViews(acc.wechatViews)
                .alipayViews(acc.alipayViews)
                .build()));
        return points;
    }

    private long eventCount(String eventType) {
        return analyticsEventRepository.countByEventType(eventType);
    }

    private long eventCountToday(String eventType, LocalDateTime start) {
        return analyticsEventRepository.countByEventTypeAndCreatedAtGreaterThanEqual(eventType, start);
    }

    private long eventCountByModel(String eventType, String modelCode) {
        return analyticsEventRepository.countByEventTypeAndModelCode(eventType, modelCode);
    }

    private long eventCountByModelToday(String eventType, String modelCode, LocalDateTime start) {
        return analyticsEventRepository.countByEventTypeAndModelCodeAndCreatedAtGreaterThanEqual(eventType, modelCode, start);
    }

    private long eventCountByChannel(String eventType, String channel) {
        return analyticsEventRepository.countByEventTypeAndChannel(eventType, channel);
    }

    private long eventCountByChannelToday(String eventType, String channel, LocalDateTime start) {
        return analyticsEventRepository.countByEventTypeAndChannelAndCreatedAtGreaterThanEqual(eventType, channel, start);
    }

    private static class TrendAccumulator {
        private long deepseekClicks;
        private long claudeClicks;
        private long deepseekSuccess;
        private long claudeSuccess;
        private long qrModalOpens;
        private long wechatViews;
        private long alipayViews;
    }
}
