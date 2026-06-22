package com.zodiac.api.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zodiac.api.dto.AdminLoginRequest;
import com.zodiac.api.dto.AdminOverviewResponse;
import com.zodiac.api.dto.AdminReportPageResponse;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.AdminAuthException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.service.AdminAuthService;
import com.zodiac.api.service.AdminDashboardService;
import com.zodiac.api.service.PayService;
import com.zodiac.api.service.ReferralService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAuthService adminAuthService;
    private final AdminDashboardService adminDashboardService;
    private final PayService payService;
    private final PayOrderRepository payOrderRepository;
    private final ReferralService referralService;

    private final Cache<String, AtomicInteger> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest httpReq) {
        String ip = IpUtil.getClientIp(httpReq);
        AtomicInteger attempts = loginAttempts.get(ip, k -> new AtomicInteger(0));
        if (attempts.incrementAndGet() > 10) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "rate_limited",
                    "message", "login attempts are too frequent, please try again later"
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
                                    @RequestParam(required = false) String query,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String normalizedStatus = normalizeOrderStatus(status);
        String normalizedChannel = normalizeOrderChannel(channel);
        Page<PayOrder> orderPage = payOrderRepository.searchOrders(normalizedStatus, normalizedChannel, query, pageRequest);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", orderPage.getContent().stream().map(this::toOrderMap).toList());
        resp.put("totalElements", orderPage.getTotalElements());
        resp.put("totalPages", orderPage.getTotalPages());
        resp.put("page", page);
        resp.put("size", size);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/orders/{outTradeNo}/confirm")
    public ResponseEntity<?> confirmOrder(HttpServletRequest request, @PathVariable String outTradeNo) {
        requireAdmin(request);
        return buildManualConfirmResponse(outTradeNo);
    }

    @PostMapping("/orders/{outTradeNo}/repair-paid")
    public ResponseEntity<?> repairPaid(HttpServletRequest request, @PathVariable String outTradeNo) {
        requireAdmin(request);
        return buildManualConfirmResponse(outTradeNo);
    }

    @PostMapping("/orders/{outTradeNo}/approve-unlock")
    public ResponseEntity<?> approveUnlock(HttpServletRequest request, @PathVariable String outTradeNo) {
        requireAdmin(request);
        return buildManualConfirmResponse(outTradeNo);
    }

    @PostMapping("/orders/{outTradeNo}/close")
    public ResponseEntity<?> closeOrder(HttpServletRequest request, @PathVariable String outTradeNo) {
        requireAdmin(request);
        var opt = payOrderRepository.findByOutTradeNo(outTradeNo);
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "order not found"
            ));
        }
        PayOrder order = opt.get();
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "paid order cannot be closed"
            ));
        }
        order.setStatus("CLOSED");
        payOrderRepository.save(order);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "order closed",
                "closedAt", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/orders/{outTradeNo}/logs")
    public ResponseEntity<?> orderLogs(HttpServletRequest request, @PathVariable String outTradeNo) {
        requireAdmin(request);
        return ResponseEntity.ok(Map.of(
                "order", outTradeNo,
                "logs", List.of()
        ));
    }

    @GetMapping("/orders/count")
    public ResponseEntity<?> orderCount(HttpServletRequest request) {
        requireAdmin(request);
        long created = payOrderRepository.countByStatus(PayOrder.STATUS_CREATED);
        long paid = payOrderRepository.countByStatus(PayOrder.STATUS_PAID);
        long closed = payOrderRepository.countByStatus("CLOSED");
        long failed = payOrderRepository.countByStatus("FAILED");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("CREATED", created);
        resp.put("PAYING", created);
        resp.put("PAID", paid);
        resp.put("CLOSED", closed);
        resp.put("FAILED", failed);
        resp.put("TOTAL", payOrderRepository.count());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/referral/overview")
    public ResponseEntity<?> referralOverview(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.getOverview());
    }

    @GetMapping("/referral/users")
    public ResponseEntity<?> referralUsers(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.getUsers());
    }

    @GetMapping("/referral/bindings")
    public ResponseEntity<?> referralBindings(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.getBindings());
    }

    @PostMapping("/referral/bindings/rebind")
    public ResponseEntity<?> referralRebind(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        referralService.rebind(
                toLong(body.get("inviteeUserId")),
                toLong(body.get("inviterUserId")),
                stringValue(body.get("source"))
        );
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/referral/rewards")
    public ResponseEntity<?> referralRewards(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.getRewards());
    }

    @PostMapping("/referral/rewards/issue")
    public ResponseEntity<?> issueReward(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        referralService.issueReward(
                toLong(body.get("payOrderId")),
                toLong(body.get("inviterUserId")),
                toNullableLong(body.get("inviteeUserId")),
                toInt(body.get("amountFen")),
                stringValue(body.get("remark"))
        );
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/referral/rewards/{rewardId}/cancel")
    public ResponseEntity<?> cancelReward(HttpServletRequest request,
                                          @PathVariable Long rewardId,
                                          @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        referralService.cancelReward(rewardId, stringValue(body.get("remark")));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/referral/withdrawals")
    public ResponseEntity<?> referralWithdrawals(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.getWithdrawals());
    }

    @PostMapping("/referral/withdrawals/{withdrawalId}/approve")
    public ResponseEntity<?> approveWithdrawal(HttpServletRequest request,
                                               @PathVariable Long withdrawalId,
                                               @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        referralService.approveWithdrawal(withdrawalId, stringValue(body.get("remark")));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/referral/withdrawals/{withdrawalId}/reject")
    public ResponseEntity<?> rejectWithdrawal(HttpServletRequest request,
                                              @PathVariable Long withdrawalId,
                                              @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        referralService.rejectWithdrawal(withdrawalId, stringValue(body.get("remark")));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private ResponseEntity<?> buildManualConfirmResponse(String outTradeNo) {
        Map<String, Object> result = payService.manualConfirm(outTradeNo);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    private Map<String, Object> toOrderMap(PayOrder order) {
        Map<String, Object> m = new LinkedHashMap<>();
        String rawStatus = order.getStatus();
        String status = PayOrder.STATUS_CREATED.equals(rawStatus) ? "PAYING" : rawStatus;
        String unlockStatus;
        if (order.getTokenConsumedAt() != null) {
            unlockStatus = "CONSUMED";
        } else if (PayOrder.STATUS_PAID.equals(rawStatus)) {
            unlockStatus = "UNLOCKED";
        } else if ("CLOSED".equals(rawStatus)) {
            unlockStatus = "EXPIRED";
        } else {
            unlockStatus = "LOCKED";
        }
        m.put("id", order.getId());
        m.put("outTradeNo", order.getOutTradeNo());
        m.put("transactionId", order.getTransactionId());
        m.put("totalFee", order.getTotalFee());
        m.put("amountFen", order.getTotalFee());
        m.put("status", status);
        m.put("rawStatus", rawStatus);
        m.put("channel", order.getChannel() == null ? "wechat" : order.getChannel());
        m.put("scene", order.getScene() == null ? "PREMIUM_REPORT" : order.getScene());
        m.put("notifyVerified", PayOrder.STATUS_PAID.equals(rawStatus) ? Boolean.TRUE : null);
        m.put("tokenConsumed", order.getTokenConsumedAt() != null);
        m.put("unlockStatus", unlockStatus);
        m.put("unlockSource", PayOrder.STATUS_PAID.equals(rawStatus) ? "PAYMENT_AUTO" : null);
        m.put("unlockGrantedAt", order.getPaidAt());
        m.put("unlockGrantedBy", PayOrder.STATUS_PAID.equals(rawStatus) ? "system" : null);
        m.put("unlockRemark", null);
        m.put("reportType", order.getReportType() == null ? "love" : order.getReportType());
        Map<String, Object> referralSnapshot = referralService.getOrderReferralSnapshot(order);
        m.put("referralUserId", referralSnapshot.get("referralUserId"));
        m.put("referralPhone", referralSnapshot.get("referralPhone"));
        m.put("referralSettled", referralSnapshot.get("referralSettled"));
        m.put("openid", order.getOpenid());
        m.put("paidAt", order.getPaidAt());
        m.put("createdAt", order.getCreatedAt());
        m.put("closedAt", null);
        m.put("failReason", null);
        return m;
    }

    private String normalizeOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase()) {
            case "PAYING" -> PayOrder.STATUS_CREATED;
            default -> status.trim().toUpperCase();
        };
    }

    private String normalizeOrderChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return switch (channel.trim().toUpperCase()) {
            case "WECHAT" -> "wechat";
            case "ALIPAY" -> "alipay";
            default -> null;
        };
    }

    private Long toLong(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("required parameter is missing");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private Long toNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return Long.parseLong(text);
    }

    private Integer toInt(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("required parameter is missing");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private void requireAdmin(HttpServletRequest request) {
        adminAuthService.requireValidToken(resolveToken(request));
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
        throw new AdminAuthException("please login as admin first");
    }
}
