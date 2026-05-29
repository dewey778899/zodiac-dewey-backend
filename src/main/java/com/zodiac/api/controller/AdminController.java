package com.zodiac.api.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zodiac.api.dto.AdminLoginRequest;
import com.zodiac.api.dto.AdminOrderLogResponse;
import com.zodiac.api.dto.AdminOverviewResponse;
import com.zodiac.api.dto.AdminReportPageResponse;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.entity.PaymentNotifyLog;
import com.zodiac.api.exception.AdminAuthException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.repository.PaymentNotifyLogRepository;
import com.zodiac.api.service.AdminAuthService;
import com.zodiac.api.service.AdminDashboardService;
import com.zodiac.api.service.PaymentFacadeService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminAuthService adminAuthService;
    private final AdminDashboardService adminDashboardService;
    private final PaymentFacadeService paymentFacadeService;
    private final PayOrderRepository payOrderRepository;
    private final PaymentNotifyLogRepository paymentNotifyLogRepository;

    private final Cache<String, AtomicInteger> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AdminLoginRequest request,
                                   HttpServletRequest httpReq) {
        String ip = IpUtil.getClientIp(httpReq);
        AtomicInteger attempts = loginAttempts.get(ip, k -> new AtomicInteger(0));
        if (attempts.incrementAndGet() > 10) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", "登录尝试过于频繁，请 15 分钟后再试"
            ));
        }
        var result = adminAuthService.login(request.getUsername(), request.getPassword());
        loginAttempts.invalidate(ip);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "token", result.token(),
                "expiresAt", result.expiresAt().toString()
        ));
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview(HttpServletRequest request) {
        requireAdmin(request);
        return adminDashboardService.getOverview();
    }

    @GetMapping("/reports")
    public AdminReportPageResponse reports(HttpServletRequest request,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String query) {
        requireAdmin(request);
        return adminDashboardService.getReports(query, page, size);
    }

    @GetMapping("/orders")
    public ResponseEntity<?> orders(HttpServletRequest request,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PayOrder> orderPage;
        String normalizedStatus = blankToNull(status);
        String normalizedChannel = normalizeChannelOrNull(channel);
        if (normalizedStatus != null && normalizedChannel != null) {
            orderPage = payOrderRepository.findByChannelAndStatusOrderByCreatedAtDesc(normalizedChannel, normalizedStatus, pageRequest);
        } else if (normalizedStatus != null) {
            orderPage = payOrderRepository.findByStatusOrderByCreatedAtDesc(normalizedStatus, pageRequest);
        } else {
            orderPage = payOrderRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", orderPage.getContent().stream().map(this::toOrderMap).toList());
        resp.put("totalElements", orderPage.getTotalElements());
        resp.put("totalPages", orderPage.getTotalPages());
        resp.put("page", page);
        resp.put("size", size);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/orders/{outTradeNo}/repair-paid")
    public ResponseEntity<?> repairPaid(HttpServletRequest request,
                                        @PathVariable String outTradeNo,
                                        @RequestBody(required = false) RepairPaidRequest body) {
        requireAdmin(request);
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new AdminAuthException("订单不存在"));
        String channel = body != null && body.getChannel() != null ? body.getChannel() : order.getChannel();
        String transactionId = body != null ? body.getTransactionId() : null;
        paymentFacadeService.repairPaid(outTradeNo, channel, transactionId, "ADMIN_REPAIR");
        return ResponseEntity.ok(paymentFacadeService.getOrderStatus(outTradeNo));
    }

    @PostMapping("/orders/{outTradeNo}/close")
    public ResponseEntity<?> closeOrder(HttpServletRequest request,
                                        @PathVariable String outTradeNo) {
        requireAdmin(request);
        paymentFacadeService.closeOrder(outTradeNo);
        return ResponseEntity.ok(paymentFacadeService.getOrderStatus(outTradeNo));
    }

    @GetMapping("/orders/{outTradeNo}/logs")
    public ResponseEntity<?> orderLogs(HttpServletRequest request,
                                       @PathVariable String outTradeNo) {
        requireAdmin(request);
        var logs = paymentNotifyLogRepository.findTop20ByOutTradeNoOrderByCreatedAtDesc(outTradeNo).stream()
                .map(this::toLogItem)
                .toList();
        return ResponseEntity.ok(AdminOrderLogResponse.builder()
                .outTradeNo(outTradeNo)
                .logs(logs)
                .build());
    }

    @GetMapping("/orders/count")
    public ResponseEntity<?> orderCount(HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("CREATED", payOrderRepository.countByStatus(PayOrder.STATUS_CREATED));
        resp.put("PAYING", payOrderRepository.countByStatus(PayOrder.STATUS_PAYING));
        resp.put("PAID", payOrderRepository.countByStatus(PayOrder.STATUS_PAID));
        resp.put("FAILED", payOrderRepository.countByStatus(PayOrder.STATUS_FAILED));
        resp.put("CLOSED", payOrderRepository.countByStatus(PayOrder.STATUS_CLOSED));
        resp.put("TOTAL", payOrderRepository.count());
        return ResponseEntity.ok(resp);
    }

    private Map<String, Object> toOrderMap(PayOrder order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", order.getId());
        m.put("outTradeNo", order.getOutTradeNo());
        m.put("channel", lower(order.getChannel()));
        m.put("scene", order.getSceneCode());
        m.put("tradeType", order.getTradeType());
        m.put("subject", order.getSubject());
        m.put("amountFen", order.getAmountFen());
        m.put("status", order.getStatus());
        m.put("reportType", order.getReportType());
        m.put("notifyVerified", order.getNotifyVerified());
        m.put("tokenConsumed", order.getTokenConsumedAt() != null);
        m.put("createdAt", order.getCreatedAt());
        m.put("paidAt", order.getPaidAt());
        m.put("closedAt", order.getClosedAt());
        m.put("expiresAt", order.getExpiresAt());
        m.put("failReason", order.getFailReason());
        return m;
    }

    private AdminOrderLogResponse.NotifyLogItem toLogItem(PaymentNotifyLog log) {
        return AdminOrderLogResponse.NotifyLogItem.builder()
                .id(log.getId())
                .channel(lower(log.getChannel()))
                .notifyType(log.getNotifyType())
                .verified(log.getVerified())
                .processResult(log.getProcessResult())
                .errorMessage(log.getErrorMessage())
                .rawPayload(log.getRawPayload())
                .createdAt(log.getCreatedAt() == null ? null : log.getCreatedAt().format(TIME))
                .build();
    }

    private void requireAdmin(HttpServletRequest request) {
        String token = resolveToken(request);
        adminAuthService.requireValidToken(token);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("X-Admin-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        throw new AdminAuthException("请先登录后台");
    }

    private String normalizeChannelOrNull(String channel) {
        if (channel == null || channel.isBlank()) return null;
        if ("wechat".equalsIgnoreCase(channel)) return PayOrder.CHANNEL_WECHAT;
        if ("alipay".equalsIgnoreCase(channel)) return PayOrder.CHANNEL_ALIPAY;
        return channel.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    @Data
    public static class RepairPaidRequest {
        private String channel;
        private String transactionId;
    }
}
