package com.zodiac.api.controller;

import com.zodiac.api.service.PayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PayController {

    private final PayService payService;

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

    @PostMapping("/api/pay/create-manual")
    public ResponseEntity<?> createManualOrder() {
        Map<String, Object> result = payService.createManualOrder();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/pay/status/{outTradeNo}")
    public ResponseEntity<?> queryStatus(@PathVariable String outTradeNo) {
        Map<String, Object> result = payService.queryStatus(outTradeNo);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/pay/manual-confirm/{outTradeNo}")
    public ResponseEntity<?> manualConfirm(@PathVariable String outTradeNo) {
        Map<String, Object> result = payService.manualConfirm(outTradeNo);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/pay/notify")
    public String notify(@RequestParam Map<String, String> params) {
        return payService.handleNotify(params);
    }

    @GetMapping("/api/pay/token/check")
    public ResponseEntity<?> checkToken(@RequestParam String token) {
        boolean valid = payService.isTokenValid(token);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @GetMapping("/api/pay/info")
    public ResponseEntity<?> getPayInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("xorPayEnabled", false);
        info.put("qrCodeAlipay", "/img/alipay_qr.jpg");
        info.put("qrCodeWechat", "/img/wechat_qr.jpg");
        info.put("totalFee", 1990);
        return ResponseEntity.ok(info);
    }
}