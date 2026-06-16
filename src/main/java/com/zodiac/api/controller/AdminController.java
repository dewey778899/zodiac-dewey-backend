package com.zodiac.api.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zodiac.api.dto.AdminLoginRequest;
import com.zodiac.api.dto.AdminOrderLogResponse;
import com.zodiac.api.dto.AdminOverviewResponse;
import com.zodiac.api.dto.AdminReportPageResponse;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.entity.PaymentNotifyLog;
import com.zodiac.api.entity.PremiumUnlockRequest;
import com.zodiac.api.exception.AdminAuthException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.repository.PaymentNotifyLogRepository;
import com.zodiac.api.service.AdminAuthService;
import com.zodiac.api.service.AdminDashboardService;
import com.zodiac.api.service.PaymentFacadeService;
import com.zodiac.api.service.PremiumUnlockService;
import com.zodiac.api.service.ReferralService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final PremiumUnlockService premiumUnlockService;
    private final ReferralService referralService;
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
        AtomicInteger attempts = loginAttempts.get(ip, key -> new AtomicInteger(0));
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

    @PostMapping("/orders/{outTradeNo}/approve-unlock")
    public ResponseEntity<?> approveUnlock(HttpServletRequest request,
                                           @PathVariable String outTradeNo,
                                           @RequestBody(required = false) UnlockActionRequest body) {
        requireAdmin(request);
        String operator = resolveToken(request);
        return ResponseEntity.ok(paymentFacadeService.adminApproveUnlock(
                outTradeNo,
                operator,
                body == null ? null : body.getRemark()
        ));
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

    @GetMapping("/premium-unlocks")
    public ResponseEntity<?> premiumUnlocks(HttpServletRequest request,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        Page<PremiumUnlockRequest> unlockPage = premiumUnlockService.listUnlocks(page, size);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", unlockPage.getContent().stream().map(premiumUnlockService::toAdminMap).toList());
        resp.put("totalElements", unlockPage.getTotalElements());
        resp.put("totalPages", unlockPage.getTotalPages());
        resp.put("page", page);
        resp.put("size", size);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/referral/overview")
    public ResponseEntity<?> referralOverview(HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("users", referralService.listUsers().size());
        resp.put("bindings", referralService.listBindings().size());
        resp.put("rewards", referralService.listRewards().size());
        resp.put("withdrawals", referralService.listWithdrawals().size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/referral/users")
    public ResponseEntity<?> referralUsers(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.listUsers().stream().map(user -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", user.getId());
            data.put("phone", user.getPhone());
            data.put("inviteCode", user.getInviteCode());
            data.put("displayName", user.getDisplayName());
            data.put("balanceFen", user.getBalanceFen());
            data.put("withdrawableFen", user.getWithdrawableFen());
            data.put("frozenFen", user.getFrozenFen());
            data.put("withdrawnFen", user.getWithdrawnFen());
            data.put("premiumPaidCount", user.getPremiumPaidCount());
            data.put("inviterEligible", user.getInviterEligible());
            data.put("wechatOpenid", user.getWechatOpenid());
            data.put("douyinOpenid", user.getDouyinOpenid());
            data.put("createdAt", user.getCreatedAt());
            return data;
        }).toList());
    }

    @GetMapping("/referral/bindings")
    public ResponseEntity<?> referralBindings(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.listBindings().stream().map(binding -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", binding.getId());
            data.put("inviterUserId", binding.getInviterUserId());
            data.put("inviteeUserId", binding.getInviteeUserId());
            data.put("inviteCode", binding.getInviteCode());
            data.put("bindSource", binding.getBindSource());
            data.put("boundAt", binding.getBoundAt());
            return data;
        }).toList());
    }

    @PostMapping("/referral/bindings/rebind")
    public ResponseEntity<?> referralRebind(HttpServletRequest request,
                                            @RequestBody RebindReferralRequest body) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.rebindInvitee(body.getInviteeUserId(), body.getInviterUserId(), body.getSource()));
    }

    @GetMapping("/referral/rewards")
    public ResponseEntity<?> referralRewards(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.listRewards().stream().map(reward -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", reward.getId());
            data.put("payOrderId", reward.getPayOrderId());
            data.put("inviterUserId", reward.getInviterUserId());
            data.put("inviteeUserId", reward.getInviteeUserId());
            data.put("amountFen", reward.getAmountFen());
            data.put("status", reward.getStatus());
            data.put("withdrawalId", reward.getWithdrawalId());
            data.put("settledAt", reward.getSettledAt());
            return data;
        }).toList());
    }

    @PostMapping("/referral/rewards/issue")
    public ResponseEntity<?> referralIssueReward(HttpServletRequest request,
                                                 @RequestBody RewardActionRequest body) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.issueReward(
                body.getPayOrderId(),
                body.getInviterUserId(),
                body.getAmountFen(),
                body.getRemark()
        ));
    }

    @PostMapping("/referral/rewards/{rewardId}/cancel")
    public ResponseEntity<?> referralCancelReward(HttpServletRequest request,
                                                  @PathVariable Long rewardId,
                                                  @RequestBody(required = false) RewardActionRequest body) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.cancelReward(rewardId, body == null ? null : body.getRemark()));
    }

    @GetMapping("/referral/withdrawals")
    public ResponseEntity<?> referralWithdrawals(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.listWithdrawals().stream().map(withdrawal -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", withdrawal.getId());
            data.put("userId", withdrawal.getUserId());
            data.put("amountFen", withdrawal.getAmountFen());
            data.put("status", withdrawal.getStatus());
            data.put("withdrawPlatform", withdrawal.getWithdrawPlatform());
            data.put("payeeAccountSnapshot", withdrawal.getPayeeAccountSnapshot());
            data.put("remark", withdrawal.getRemark());
            data.put("createdAt", withdrawal.getCreatedAt());
            data.put("updatedAt", withdrawal.getUpdatedAt());
            return data;
        }).toList());
    }

    @PostMapping("/referral/withdrawals/{withdrawalId}/approve")
    public ResponseEntity<?> approveWithdrawal(HttpServletRequest request,
                                               @PathVariable Long withdrawalId,
                                               @RequestBody(required = false) WithdrawalActionRequest body) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.approveWithdrawal(withdrawalId, body == null ? null : body.getRemark()));
    }

    @PostMapping("/referral/withdrawals/{withdrawalId}/reject")
    public ResponseEntity<?> rejectWithdrawal(HttpServletRequest request,
                                              @PathVariable Long withdrawalId,
                                              @RequestBody(required = false) WithdrawalActionRequest body) {
        requireAdmin(request);
        return ResponseEntity.ok(referralService.rejectWithdrawal(withdrawalId, body == null ? null : body.getRemark()));
    }

    private Map<String, Object> toOrderMap(PayOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", order.getId());
        data.put("outTradeNo", order.getOutTradeNo());
        data.put("channel", lower(order.getChannel()));
        data.put("scene", order.getSceneCode());
        data.put("tradeType", order.getTradeType());
        data.put("subject", order.getSubject());
        data.put("amountFen", order.getAmountFen());
        data.put("status", order.getStatus());
        data.put("reportType", order.getReportType());
        data.put("notifyVerified", order.getNotifyVerified());
        data.put("tokenConsumed", order.getTokenConsumedAt() != null);
        data.put("unlockStatus", order.getUnlockStatus());
        data.put("unlockSource", order.getUnlockSource());
        data.put("unlockGrantedAt", order.getUnlockGrantedAt());
        data.put("unlockGrantedBy", order.getUnlockGrantedBy());
        data.put("unlockRemark", order.getUnlockRemark());
        data.put("referralUserId", order.getReferralUserId());
        data.put("referralSettled", order.getReferralSettled());
        data.put("createdAt", order.getCreatedAt());
        data.put("paidAt", order.getPaidAt());
        data.put("closedAt", order.getClosedAt());
        data.put("expiresAt", order.getExpiresAt());
        data.put("failReason", order.getFailReason());
        return data;
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
        if ("douyin".equalsIgnoreCase(channel) || "tt".equalsIgnoreCase(channel)) return PayOrder.CHANNEL_DOUYIN;
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

    @Data
    public static class RebindReferralRequest {
        private Long inviteeUserId;
        private Long inviterUserId;
        private String source;
    }

    @Data
    public static class RewardActionRequest {
        private Long payOrderId;
        private Long inviterUserId;
        private Integer amountFen;
        private String remark;
    }

    @Data
    public static class WithdrawalActionRequest {
        private String remark;
    }

    @Data
    public static class UnlockActionRequest {
        private String remark;
    }
}
