package com.zodiac.api.controller;

import com.zodiac.api.service.PayService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PayController {

    private final PayService payService;

    /**
     * 创建支付订单（走 PayJS）
     * POST /api/pay/create
     */
    @PostMapping("/api/pay/create")
    public ResponseEntity<?> createOrder() {
        try {
            Map<String, Object> result = payService.createOrder();
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "pay_service_unavailable",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 创建手动订单（不走 PayJS，用于静态收款码 + 后台确认模式）
     * POST /api/pay/create-manual
     */
    @PostMapping("/api/pay/create-manual")
    public ResponseEntity<?> createManualOrder() {
        Map<String, Object> result = payService.createManualOrder();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/pay/orders")
    public ResponseEntity<?> createManagedOrder(@RequestBody PayOrderCreateRequest request) {
        return ResponseEntity.ok(payService.createPaymentOrder(
                request.getChannel(),
                request.getScene(),
                request.getReportType(),
                request.getAmountFen(),
                request.getSubject(),
                request.getReturnUrl(),
                request.getPhone()
        ));
    }

    /**
     * 查询支付状态 (前端轮询)
     * GET /api/pay/status/{outTradeNo}
     */
    @GetMapping("/api/pay/status/{outTradeNo}")
    public ResponseEntity<?> queryStatus(@PathVariable String outTradeNo) {
        Map<String, Object> result = payService.queryStatus(outTradeNo);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/pay/orders/{outTradeNo}")
    public ResponseEntity<?> fetchOrder(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(payService.getOrderForClient(outTradeNo));
    }

    /**
     * 手动确认支付（静态收款码模式）
     * POST /api/pay/manual-confirm/{outTradeNo}
     */
    @PostMapping("/api/pay/manual-confirm/{outTradeNo}")
    public ResponseEntity<?> manualConfirm(@PathVariable String outTradeNo) {
        Map<String, Object> result = payService.manualConfirm(outTradeNo);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/pay/dev/mark-paid/{outTradeNo}")
    public ResponseEntity<?> devMarkPaid(@PathVariable String outTradeNo) {
        payService.manualConfirm(outTradeNo);
        return ResponseEntity.ok(payService.getOrderForClient(outTradeNo));
    }

    @PostMapping("/api/pay/wechat/jsapi-prepare")
    public ResponseEntity<?> prepareWechatJsapi(@RequestBody PayOrderCreateRequest request) {
        PayOrderCreateRequest normalized = new PayOrderCreateRequest();
        normalized.setChannel("wechat");
        normalized.setScene(request.getScene() == null ? "wechat_jsapi" : request.getScene());
        normalized.setReportType(request.getReportType());
        normalized.setAmountFen(request.getAmountFen());
        normalized.setSubject(request.getSubject());
        normalized.setReturnUrl(request.getReturnUrl());
        normalized.setPhone(request.getPhone());
        return ResponseEntity.ok(payService.createPaymentOrder(
                normalized.getChannel(),
                normalized.getScene(),
                normalized.getReportType(),
                normalized.getAmountFen(),
                normalized.getSubject(),
                normalized.getReturnUrl(),
                normalized.getPhone()
        ));
    }

    /**
     * PayJS 异步回调 (服务端到服务端, 不需要 CORS)
     * POST /api/pay/notify
     */
    @PostMapping("/api/pay/notify")
    public String notify(@RequestParam Map<String, String> params) {
        return payService.handleNotify(params);
    }

    /**
     * 校验 accessToken 是否有效（不消耗），前端预检用
     * GET /api/pay/token/check?token=xxx
     */
    @GetMapping("/api/pay/token/check")
    public ResponseEntity<?> checkToken(@RequestParam String token) {
        boolean valid = payService.isTokenValid(token);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    public static class PayOrderCreateRequest {
        private String channel;
        private String scene;
        private String reportType;
        private Integer amountFen;
        private String subject;
        private String returnUrl;
        private String phone;

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }
        public String getReportType() { return reportType; }
        public void setReportType(String reportType) { this.reportType = reportType; }
        public Integer getAmountFen() { return amountFen; }
        public void setAmountFen(Integer amountFen) { this.amountFen = amountFen; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }
}
