package com.zodiac.api.controller;

import com.zodiac.api.dto.CompatibilityRequest;
import com.zodiac.api.dto.CompatibilityResponse;
import com.zodiac.api.dto.WechatUpdateRequest;
import com.zodiac.api.repository.SoulmateReportRepository;
import com.zodiac.api.service.AnalyticsService;
import com.zodiac.api.service.AiServiceException;
import com.zodiac.api.service.CompatibilityService;
import com.zodiac.api.service.RateLimitService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CompatibilityController {

    private final CompatibilityService compatibilityService;
    private final RateLimitService rateLimitService;
    private final SoulmateReportRepository repository;
    private final AnalyticsService analyticsService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "zodiac-api",
                "global_used", rateLimitService.getGlobalUsed(),
                "global_total", rateLimitService.getDailyTotal()
        );
    }

    @PostMapping("/compatibility")
    public ResponseEntity<?> generate(@Valid @RequestBody CompatibilityRequest request,
                                       HttpServletRequest httpReq) {
        String ip = IpUtil.getClientIp(httpReq);
        String ua = httpReq.getHeader("User-Agent");

        String businessValidationError = validateBusinessRequest(request);
        if (businessValidationError != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "validation_failed",
                    "message", businessValidationError
            ));
        }

        // 1. 限流检查（含 Claude 专属配额）
        String limitMsg = rateLimitService.tryAcquire(ip, request.getModel());
        if (limitMsg != null) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", limitMsg
            ));
        }

        // 2. 生成报告
        try {
            CompatibilityResponse resp = compatibilityService.generateReport(request, ip, ua);
            analyticsService.recordGenerateSuccess(request.getModel(), resp.getReportUid(), ip, ua);
            return ResponseEntity.ok(resp);
        } catch (AiServiceException e) {
            log.error("AI 生成失败: reason={}, msg={}", e.getReason(), e.getMessage(), e);
            rateLimitService.rollback(ip, request.getModel());
            boolean isPremium = "claude".equalsIgnoreCase(request.getModel());
            if (isPremium) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "ai_service_failed",
                        "message", "深度解析服务暂时不可用，请稍后重试。如已付费，可添加微信 hellodewey 联系退费。",
                        "reason", e.getReason().name(),
                        "refundHint", "添加微信 hellodewey 可申请退费"
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                    "error", "generation_failed",
                    "message", e.getMessage() != null ? e.getMessage() : "生成失败，请稍后重试"
            ));
        } catch (Exception e) {
            log.error("生成报告失败,回滚限流计数", e);
            rateLimitService.rollback(ip, request.getModel());
            boolean isPremium = "claude".equalsIgnoreCase(request.getModel());
            if (isPremium) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "generation_failed",
                        "message", "深度解析服务暂时不可用，请稍后重试。如已付费，可添加微信 hellodewey 联系退费。",
                        "refundHint", "添加微信 hellodewey 可申请退费"
                ));
            }
            return ResponseEntity.status(500).body(Map.of(
                    "error", "generation_failed",
                    "message", e.getMessage() != null ? e.getMessage() : "生成失败"
            ));
        }
    }

    @PostMapping("/wechat")
    public ResponseEntity<?> updateWechat(@Valid @RequestBody WechatUpdateRequest req,
                                           HttpServletRequest httpReq) {
        String ip = IpUtil.getClientIp(httpReq);
        if (!rateLimitService.tryAcquireWechat(ip)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", "请求过于频繁，请稍后再试"
            ));
        }
        int updated = repository.updateWechatId(req.getReportUid(), req.getWechatId());
        if (updated > 0) {
            return ResponseEntity.ok(Map.of("status", "ok", "message", "提交成功,小登哥会看到你的消息 💕"));
        }
        return ResponseEntity.status(404).body(Map.of(
                "error", "not_found",
                "message", "报告编号不存在"
        ));
    }

    @PostMapping("/share/{uid}")
    public ResponseEntity<?> recordShare(@PathVariable String uid) {
        repository.incrementShareCount(uid);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/compatibility/report/{uid}")
    public ResponseEntity<?> getReport(@PathVariable String uid) {
        Optional<CompatibilityResponse> report = compatibilityService.getReportByUid(uid);
        if (report.isPresent()) {
            return ResponseEntity.ok(report.get());
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "not_found",
                    "message", "该报告不存在或已过期"
            ));
        }
    }

    private String validateBusinessRequest(CompatibilityRequest request) {
        String reportType = request.getReportType();
        boolean isLove = reportType == null
                || reportType.isBlank()
                || CompatibilityRequest.REPORT_TYPE_LOVE.equalsIgnoreCase(reportType);
        if (isLove && request.getPersonB() == null) {
            return "爱情合盘需要提供 TA 的信息";
        }
        if (!isLove && !CompatibilityRequest.REPORT_TYPE_CAREER.equalsIgnoreCase(reportType)
                && !CompatibilityRequest.REPORT_TYPE_WEALTH.equalsIgnoreCase(reportType)) {
            return "不支持的报告类型";
        }
        return null;
    }
}
