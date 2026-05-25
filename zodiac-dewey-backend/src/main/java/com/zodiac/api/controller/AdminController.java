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
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
        try {
            var result = adminAuthService.login(request.getUsername(), request.getPassword());
            loginAttempts.invalidate(ip);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "token", result.token(),
                    "expiresAt", result.expiresAt().toString()
            ));
        } catch (AdminAuthException e) {
            throw e;
        }
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

    /**
     * 订单列表（分页，可按状态筛选）
     * GET /api/admin/orders?status=CREATED&page=0&size=20
     */
    @GetMapping("/orders")
    public ResponseEntity<?> orders(HttpServletRequest request,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PayOrder> orderPage;
        if (status != null && !status.isBlank()) {
            orderPage = payOrderRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
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

    /**
     * 确认订单已支付（手动）
     * POST /api/admin/orders/{outTradeNo}/confirm
     */
    @PostMapping("/orders/{outTradeNo}/confirm")
    public ResponseEntity<?> confirmOrder(HttpServletRequest request,
                                           @PathVariable String outTradeNo) {
        requireAdmin(request);
        Map<String, Object> result = payService.manualConfirm(outTradeNo);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 各状态订单计数
     * GET /api/admin/orders/count
     */
    @GetMapping("/orders/count")
    public ResponseEntity<?> orderCount(HttpServletRequest request) {
        requireAdmin(request);
        long created = payOrderRepository.countByStatus(PayOrder.STATUS_CREATED);
        long paid = payOrderRepository.countByStatus(PayOrder.STATUS_PAID);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("CREATED", created);
        resp.put("PAID", paid);
        resp.put("TOTAL", created + paid);
        return ResponseEntity.ok(resp);
    }

    private Map<String, Object> toOrderMap(PayOrder order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", order.getId());
        m.put("outTradeNo", order.getOutTradeNo());
        m.put("totalFee", order.getTotalFee());
        m.put("status", order.getStatus());
        m.put("paidAt", order.getPaidAt());
        m.put("createdAt", order.getCreatedAt());
        return m;
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
}
