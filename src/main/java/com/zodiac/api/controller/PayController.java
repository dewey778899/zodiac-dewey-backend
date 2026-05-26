package com.zodiac.api.controller;

import com.zodiac.api.service.PayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 查询支付状态 (前端轮询)
     * GET /api/pay/status/{outTradeNo}
     */
    @GetMapping("/api/pay/status/{outTradeNo}")
    public ResponseEntity<?> queryStatus(@PathVariable String outTradeNo) {
        Map<String, Object> result = payService.queryStatus(outTradeNo);
        return ResponseEntity.ok(result);
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
}
