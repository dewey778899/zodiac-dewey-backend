package com.zodiac.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.dto.WechatJsapiPrepareRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.service.PaymentFacadeService;
import com.zodiac.api.service.PaymentNotifyService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PayController {

    private final PaymentFacadeService paymentFacadeService;
    private final PaymentNotifyService paymentNotifyService;
    private final PayOrderRepository payOrderRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/pay/orders")
    public ResponseEntity<?> createOrder(@Valid @RequestBody PaymentCreateOrderRequest request,
                                         HttpServletRequest httpRequest) {
        return ResponseEntity.ok(paymentFacadeService.createOrder(request, IpUtil.getClientIp(httpRequest)));
    }

    @GetMapping("/api/pay/orders/{outTradeNo}")
    public ResponseEntity<?> getOrder(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(paymentFacadeService.getOrderStatus(outTradeNo));
    }

    @PostMapping("/api/pay/wechat/jsapi-prepare")
    public ResponseEntity<?> prepareWechatJsapi(@Valid @RequestBody WechatJsapiPrepareRequest request,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(paymentFacadeService.prepareWechatJsapi(request, IpUtil.getClientIp(httpRequest)));
    }

    @PostMapping("/api/pay/orders/{outTradeNo}/consume")
    public ResponseEntity<?> consume(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(paymentFacadeService.consumeOrderToken(outTradeNo));
    }

    @PostMapping("/api/pay/notify/wechat")
    public ResponseEntity<?> notifyWechat(@RequestBody(required = false) String rawBody,
                                          @RequestParam Map<String, String> params) {
        String outTradeNo = params.getOrDefault("out_trade_no", params.get("outTradeNo"));
        String transactionId = params.getOrDefault("transaction_id", params.get("transactionId"));
        String raw = rawBody == null || rawBody.isBlank() ? safeJson(params) : rawBody;
        boolean verified = Boolean.parseBoolean(params.getOrDefault("verified", "false"));
        if (outTradeNo == null || outTradeNo.isBlank()) {
            paymentNotifyService.writeLog(null, PayOrder.CHANNEL_WECHAT, "PAYMENT", false, "IGNORED", "缺少订单号", raw);
            return ResponseEntity.badRequest().body(Map.of("code", "FAIL", "message", "missing out_trade_no"));
        }
        paymentNotifyService.handlePaidNotification(PayOrder.CHANNEL_WECHAT, outTradeNo, raw, verified, transactionId);
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
    }

    @PostMapping("/api/pay/notify/alipay")
    public String notifyAlipay(@RequestParam Map<String, String> params,
                               @RequestBody(required = false) String rawBody) {
        String outTradeNo = params.getOrDefault("out_trade_no", params.get("outTradeNo"));
        String transactionId = params.getOrDefault("trade_no", params.get("tradeNo"));
        String raw = rawBody == null || rawBody.isBlank() ? safeJson(params) : rawBody;
        boolean verified = Boolean.parseBoolean(params.getOrDefault("verified", "false"));
        if (outTradeNo == null || outTradeNo.isBlank()) {
            paymentNotifyService.writeLog(null, PayOrder.CHANNEL_ALIPAY, "PAYMENT", false, "IGNORED", "缺少订单号", raw);
            return "fail";
        }
        paymentNotifyService.handlePaidNotification(PayOrder.CHANNEL_ALIPAY, outTradeNo, raw, verified, transactionId);
        return verified ? "success" : "fail";
    }

    @Deprecated
    @PostMapping("/api/pay/create-manual")
    public ResponseEntity<?> createManualOrder() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "manual_payment_deprecated",
                "message", "静态收款码手动确认已废弃，请使用 /api/pay/orders"
        ));
    }

    @Deprecated
    @PostMapping("/api/pay/manual-confirm/{outTradeNo}")
    public ResponseEntity<?> manualConfirm(@PathVariable String outTradeNo) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "manual_payment_deprecated",
                "message", "手动确认已迁移到后台 /api/admin/orders/{outTradeNo}/repair-paid"
        ));
    }

    @Deprecated
    @GetMapping("/api/pay/status/{outTradeNo}")
    public ResponseEntity<?> legacyStatus(@PathVariable String outTradeNo) {
        return getOrder(outTradeNo);
    }

    @GetMapping("/api/pay/token/check")
    public ResponseEntity<?> checkToken(@RequestParam String token) {
        return ResponseEntity.ok(Map.of("valid", paymentFacadeService.isTokenValid(token)));
    }

    @PostMapping("/api/pay/dev/mark-paid/{outTradeNo}")
    public ResponseEntity<?> devMarkPaid(@PathVariable String outTradeNo) {
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "订单不存在", HttpStatus.NOT_FOUND));
        paymentFacadeService.repairPaid(outTradeNo, order.getChannel(), "DEV_MARK_PAID", "DEV_MARK_PAID");
        return ResponseEntity.ok(paymentFacadeService.getOrderStatus(outTradeNo));
    }

    private String safeJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(params));
        } catch (Exception e) {
            return String.valueOf(params);
        }
    }
}
