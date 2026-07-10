package com.zodiac.api.controller;

import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.dto.WechatJsapiPrepareRequest;
import com.zodiac.api.service.PayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PayController {

    private final PayService payService;

    @PostMapping("/api/pay/create")
    public ResponseEntity<?> createOrder() {
        return ResponseEntity.ok(payService.createOrder());
    }

    @PostMapping("/api/pay/create-manual")
    public ResponseEntity<?> createManualOrder() {
        return ResponseEntity.ok(payService.createManualOrder());
    }

    @PostMapping("/api/pay/orders")
    public ResponseEntity<?> createManagedOrder(@Valid @RequestBody PaymentCreateOrderRequest request) {
        return ResponseEntity.ok(payService.createPaymentOrder(request));
    }

    @GetMapping("/api/pay/status/{outTradeNo}")
    public ResponseEntity<?> queryStatus(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(payService.queryStatus(outTradeNo));
    }

    @GetMapping("/api/pay/orders/{outTradeNo}")
    public ResponseEntity<?> fetchOrder(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(payService.getOrderForClient(outTradeNo));
    }

    @PostMapping("/api/pay/manual-confirm/{outTradeNo}")
    public ResponseEntity<?> manualConfirm(@PathVariable String outTradeNo) {
        return ResponseEntity.ok(payService.manualConfirm(outTradeNo));
    }

    @PostMapping("/api/pay/dev/mark-paid/{outTradeNo}")
    public ResponseEntity<?> devMarkPaid(@PathVariable String outTradeNo) {
        payService.manualConfirm(outTradeNo);
        return ResponseEntity.ok(payService.getOrderForClient(outTradeNo));
    }

    @PostMapping("/api/pay/wechat/jsapi-prepare")
    public ResponseEntity<?> prepareWechatJsapi(@Valid @RequestBody WechatJsapiPrepareRequest request,
                                                HttpServletRequest servletRequest) {
        PaymentCreateOrderRequest order = request.getOrder() == null ? new PaymentCreateOrderRequest() : request.getOrder();
        order.setChannel("wechat");
        order.setScene(order.getScene() == null ? "wechat_jsapi" : order.getScene());
        order.setOpenid(payService.exchangeWechatOpenid(request.getCode()));
        if (order.getClientContext() == null) {
            PaymentCreateOrderRequest.ClientContext clientContext = new PaymentCreateOrderRequest.ClientContext();
            clientContext.setSource("miniapp");
            clientContext.setUserAgent(servletRequest.getHeader("User-Agent"));
            clientContext.setInsideWechat(Boolean.TRUE);
            clientContext.setMobile(Boolean.TRUE);
            order.setClientContext(clientContext);
        }
        return ResponseEntity.ok(payService.createPaymentOrder(order));
    }

    @PostMapping(value = "/api/pay/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String notify(@RequestParam Map<String, String> params) {
        return payService.handleNotify(params);
    }

    @PostMapping(value = "/api/pay/wechat/notify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String wechatNotify(@RequestBody String body,
                               @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
                               @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
                               @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
                               @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        return payService.handleWechatNotify(body, timestamp, nonce, serial, signature);
    }

    @PostMapping(value = "/api/pay/alipay/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String alipayNotify(@RequestParam Map<String, String> params) {
        return payService.handleAlipayNotify(params);
    }

    @GetMapping("/api/pay/token/check")
    public ResponseEntity<?> checkToken(@RequestParam String token) {
        return ResponseEntity.ok(Map.of("valid", payService.isTokenValid(token)));
    }
}
