package com.zodiac.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.config.PayJsConfig;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.util.PayJsSignUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayService {

    private static final String PAYJS_NATIVE_URL = "https://payjs.cn/api/native";
    private static final String PAYJS_CHECK_URL = "https://payjs.cn/api/check";
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOTAL_FEE = 1990;
    private static final SecureRandom RNG = new SecureRandom();

    private final PayJsConfig config;
    private final PayOrderRepository payOrderRepository;
    private final ReferralService referralService;
    private final WechatPayService wechatPayService;
    private final AlipayService alipayService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> createOrder() {
        String outTradeNo = "ZD" + generateRandom(12);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mchid", config.getMchid());
        params.put("total_fee", TOTAL_FEE);
        params.put("out_trade_no", outTradeNo);
        params.put("body", "内容查看服务");
        if (config.getNotifyUrl() != null && !config.getNotifyUrl().isBlank()) {
            params.put("notify_url", config.getNotifyUrl());
        }
        params.put("sign", PayJsSignUtil.sign(params, config.getKey()));

        try {
            String responseBody = httpPostForm(PAYJS_NATIVE_URL, params);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            int returnCode = toInt(result.get("return_code"));
            if (returnCode != 1) {
                String msg = String.valueOf(result.getOrDefault("return_msg", "未知错误"));
                throw new RuntimeException("支付下单失败: " + msg);
            }

            PayOrder order = new PayOrder();
            order.setOutTradeNo(outTradeNo);
            order.setPayjsOrderId(String.valueOf(result.get("payjs_order_id")));
            order.setTotalFee(TOTAL_FEE);
            order.setAmountFen(TOTAL_FEE);
            order.setStatus(PayOrder.STATUS_CREATED);
            order.setChannel("wechat");
            order.setScene("payjs_native");
            order.setSceneCode("payjs_native");
            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
            order.setReportType("love");
            order.setSubject("内容查看服务");
            payOrderRepository.save(order);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("outTradeNo", outTradeNo);
            resp.put("payjsOrderId", result.get("payjs_order_id"));
            resp.put("qrcode", result.get("qrcode"));
            resp.put("codeUrl", result.get("code_url"));
            resp.put("totalFee", TOTAL_FEE);
            return resp;
        } catch (Exception e) {
            log.error("PayJS native create failed", e);
            throw new RuntimeException("支付服务暂时不可用，请稍后重试");
        }
    }

    public String handleNotify(Map<String, String> params) {
        log.info("Received PayJS notify: outTradeNo={}, returnCode={}", params.get("out_trade_no"), params.get("return_code"));
        if (!PayJsSignUtil.verifySign(params, config.getKey())) {
            log.warn("PayJS notify verify failed: outTradeNo={}", params.get("out_trade_no"));
            return "";
        }
        if (!"1".equals(params.get("return_code"))) {
            return "";
        }
        String outTradeNo = params.get("out_trade_no");
        payOrderRepository.findByOutTradeNo(outTradeNo).ifPresent(order -> markOrderPaid(
                order,
                params.get("transaction_id"),
                params.get("openid"),
                "PAYJS",
                true,
                rawJson(params)
        ));
        return "success";
    }

    public Map<String, Object> queryStatus(String outTradeNo) {
        Optional<PayOrder> opt = payOrderRepository.findByOutTradeNo(outTradeNo);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", outTradeNo);
        if (opt.isEmpty()) {
            resp.put("status", "NOT_FOUND");
            return resp;
        }

        PayOrder order = opt.get();
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            ensureTokenGenerated(order);
            payOrderRepository.save(order);
            return buildPaidStatus(order);
        }

        trySyncOrderState(order);
        order = payOrderRepository.findByOutTradeNo(outTradeNo).orElse(order);
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            return buildPaidStatus(order);
        }

        resp.put("status", normalizePendingStatus(order));
        resp.put("paid", false);
        resp.put("unlockStatus", "LOCKED");
        resp.put("unlockSource", null);
        return resp;
    }

    public boolean consumeToken(String token) {
        if (token == null || token.isBlank()) return false;
        Optional<PayOrder> opt = payOrderRepository.findByAccessToken(token);
        if (opt.isEmpty()) return false;
        PayOrder order = opt.get();
        if (!PayOrder.STATUS_PAID.equals(order.getStatus())) return false;
        if (order.getTokenConsumedAt() != null) return false;
        if (order.getPaidAt() != null
                && order.getPaidAt().plusHours(PayOrder.TOKEN_EXPIRE_HOURS).isBefore(LocalDateTime.now())) {
            return false;
        }
        order.setTokenConsumedAt(LocalDateTime.now());
        payOrderRepository.save(order);
        return true;
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) return false;
        Optional<PayOrder> opt = payOrderRepository.findByAccessToken(token);
        if (opt.isEmpty()) return false;
        PayOrder order = opt.get();
        return PayOrder.STATUS_PAID.equals(order.getStatus())
                && order.getTokenConsumedAt() == null
                && (order.getPaidAt() == null
                || !order.getPaidAt().plusHours(PayOrder.TOKEN_EXPIRE_HOURS).isBefore(LocalDateTime.now()));
    }

    public Map<String, Object> createManualOrder() {
        PaymentCreateOrderRequest request = new PaymentCreateOrderRequest();
        request.setChannel("wechat");
        request.setScene("manual_qr");
        request.setReportType("love");
        request.setAmountFen(TOTAL_FEE);
        request.setSubject("内容查看服务");
        return createPaymentOrder(request);
    }

    public Map<String, Object> createPaymentOrder(String channel,
                                                  String scene,
                                                  String reportType,
                                                  Integer amountFen,
                                                  String subject,
                                                  String returnUrl,
                                                  String phone) {
        PaymentCreateOrderRequest request = new PaymentCreateOrderRequest();
        request.setChannel(channel);
        request.setScene(scene);
        request.setReportType(reportType);
        request.setAmountFen(amountFen);
        request.setSubject(subject);
        request.setReturnUrl(returnUrl);
        request.setPhone(phone);
        return createPaymentOrder(request);
    }

    public Map<String, Object> createPaymentOrder(PaymentCreateOrderRequest request) {
        String outTradeNo = request.getOutTradeNo();
        if (outTradeNo == null || outTradeNo.isBlank()) {
            outTradeNo = "ZD" + generateRandom(12);
        }

        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo).orElseGet(PayOrder::new);
        int finalAmount = request.getAmountFen() != null && request.getAmountFen() > 0 ? request.getAmountFen() : TOTAL_FEE;
        String channel = normalizeChannel(request.getChannel());
        String scene = normalizeScene(request.getScene(), channel);

        order.setOutTradeNo(outTradeNo);
        order.setStatus(PayOrder.STATUS_CREATED);
        order.setChannel(channel);
        order.setScene(scene);
        order.setSceneCode(scene);
        order.setReportType(isBlank(request.getReportType()) ? "love" : request.getReportType());
        order.setSubject(isBlank(request.getSubject()) ? "扩展内容服务" : request.getSubject());
        order.setAmountFen(finalAmount);
        order.setTotalFee(finalAmount);
        order.setReturnUrl(request.getReturnUrl());
        order.setPhone(request.getPhone());
        order.setOpenid(request.getOpenid());
        if (request.getClientContext() != null) {
            order.setClientIp(extractClientIp(request));
        }

        Map<String, Object> payPayload = "alipay".equals(channel)
                ? alipayService.buildPayPayload(order, request)
                : wechatPayService.buildPayPayload(order, request);
        payOrderRepository.save(order);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", order.getOutTradeNo());
        resp.put("totalFee", finalAmount);
        resp.put("amountFen", finalAmount);
        resp.put("channel", order.getChannel());
        resp.put("scene", order.getScene());
        resp.put("status", order.getStatus());
        resp.put("paid", false);
        resp.put("unlockStatus", "LOCKED");
        resp.put("payPayload", payPayload);
        return resp;
    }

    public Map<String, Object> manualConfirm(String outTradeNo) {
        Optional<PayOrder> opt = payOrderRepository.findByOutTradeNo(outTradeNo);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", outTradeNo);
        if (opt.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "订单不存在");
            return resp;
        }
        PayOrder order = opt.get();
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            resp.put("success", false);
            resp.put("message", "订单已支付，无需重复确认");
            resp.put("accessToken", order.getAccessToken());
            return resp;
        }
        markOrderPaid(order, order.getTransactionId(), order.getOpenid(), "MANUAL", true, "{\"source\":\"manual_confirm\"}");
        resp.put("success", true);
        resp.put("message", "确认成功");
        resp.put("accessToken", order.getAccessToken());
        return resp;
    }

    public Map<String, Object> getOrderForClient(String outTradeNo) {
        Optional<PayOrder> opt = payOrderRepository.findByOutTradeNo(outTradeNo);
        if (opt.isEmpty()) {
            return Map.of("outTradeNo", outTradeNo, "paid", false, "status", "NOT_FOUND");
        }
        PayOrder order = opt.get();
        Map<String, Object> state = queryStatus(outTradeNo);
        boolean paid = Boolean.TRUE.equals(state.get("paid")) || "PAID".equals(state.get("status"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", outTradeNo);
        resp.put("channel", order.getChannel());
        resp.put("scene", order.getScene());
        resp.put("status", state.get("status"));
        resp.put("amountFen", order.getAmountFen());
        resp.put("paid", paid);
        resp.put("accessToken", state.get("accessToken"));
        resp.put("unlockSource", paid ? "payment_auto" : null);
        resp.put("unlockStatus", order.getTokenConsumedAt() != null ? "CONSUMED" : paid ? "UNLOCKED" : "LOCKED");
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("alipay".equals(order.getChannel())) {
            payload.put("payUrl", order.getAlipayPayUrl());
        } else {
            payload.put("mwebUrl", order.getWechatMwebUrl());
            payload.put("codeUrl", order.getWechatCodeUrl());
            payload.put("prepayId", order.getWechatPrepayId());
        }
        resp.put("payPayload", payload);
        return resp;
    }

    public String handleWechatNotify(String body,
                                     String timestamp,
                                     String nonce,
                                     String serial,
                                     String signature) {
        boolean verified = wechatPayService.verifyCallback(timestamp, nonce, body, serial, signature);
        Map<String, Object> payload = wechatPayService.decryptCallbackResource(body);
        Map<String, Object> resource = extractWechatResource(payload);
        String outTradeNo = asString(resource.get("out_trade_no"));
        String tradeState = asString(resource.get("trade_state"));
        if (!verified || !"SUCCESS".equalsIgnoreCase(tradeState)) {
            recordNotifyOnly(outTradeNo, "WECHAT", verified, body);
            return "{\"code\":\"FAIL\",\"message\":\"ignored\"}";
        }
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "支付订单不存在", HttpStatus.NOT_FOUND));
        markOrderPaid(order,
                asString(resource.get("transaction_id")),
                extractWechatOpenid(resource),
                "WECHAT",
                true,
                body);
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }

    public String handleAlipayNotify(Map<String, String> params) {
        String outTradeNo = params.get("out_trade_no");
        boolean verified = alipayService.verifyNotify(params);
        String tradeStatus = params.get("trade_status");
        if (!verified || !"TRADE_SUCCESS".equalsIgnoreCase(tradeStatus) && !"TRADE_FINISHED".equalsIgnoreCase(tradeStatus)) {
            recordNotifyOnly(outTradeNo, "ALIPAY", verified, rawJson(params));
            return "failure";
        }
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "支付订单不存在", HttpStatus.NOT_FOUND));
        order.setAlipayTradeNo(params.get("trade_no"));
        markOrderPaid(order,
                params.get("trade_no"),
                order.getOpenid(),
                "ALIPAY",
                true,
                rawJson(params));
        return "success";
    }

    public String exchangeWechatOpenid(String code) {
        return wechatPayService.exchangeOpenid(code);
    }

    private void trySyncOrderState(PayOrder order) {
        if ("wechat".equals(order.getChannel())) {
            syncWechatOrder(order);
            return;
        }
        if ("alipay".equals(order.getChannel())) {
            return;
        }
        syncPayjsOrder(order);
    }

    private void syncWechatOrder(PayOrder order) {
        try {
            Map<String, Object> remote = wechatPayService.queryOrderByOutTradeNo(order.getOutTradeNo());
            String tradeState = asString(remote.get("trade_state"));
            if ("SUCCESS".equalsIgnoreCase(tradeState)) {
                markOrderPaid(order,
                        asString(remote.get("transaction_id")),
                        extractWechatOpenid(remote),
                        "WECHAT_QUERY",
                        true,
                        rawJson(remote));
            }
        } catch (PaymentException ex) {
            log.warn("Wechat order sync skipped: outTradeNo={}, code={}", order.getOutTradeNo(), ex.getErrorCode());
        }
    }

    private void syncPayjsOrder(PayOrder order) {
        if (order.getPayjsOrderId() == null || order.getPayjsOrderId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> checkParams = new LinkedHashMap<>();
            checkParams.put("payjs_order_id", order.getPayjsOrderId());
            checkParams.put("sign", PayJsSignUtil.sign(checkParams, config.getKey()));
            String body = httpPostForm(PAYJS_CHECK_URL, checkParams);
            @SuppressWarnings("unchecked")
            Map<String, Object> checkResult = objectMapper.readValue(body, Map.class);
            if (toInt(checkResult.get("status")) == 1) {
                markOrderPaid(order,
                        asString(checkResult.get("transaction_id")),
                        order.getOpenid(),
                        "PAYJS_QUERY",
                        true,
                        rawJson(checkResult));
            }
        } catch (Exception ex) {
            log.warn("PayJS check failed: outTradeNo={}", order.getOutTradeNo(), ex);
        }
    }

    private Map<String, Object> buildPaidStatus(PayOrder order) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", order.getOutTradeNo());
        resp.put("status", "PAID");
        resp.put("paid", true);
        resp.put("accessToken", order.getAccessToken());
        resp.put("unlockStatus", order.getTokenConsumedAt() != null ? "CONSUMED" : "UNLOCKED");
        resp.put("unlockSource", "payment_auto");
        return resp;
    }

    private void markOrderPaid(PayOrder order,
                               String transactionId,
                               String openid,
                               String notifyType,
                               boolean notifyVerified,
                               String rawPayload) {
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            if (order.getNotifyType() == null) {
                order.setNotifyType(notifyType);
            }
            if (order.getNotifyVerified() == null) {
                order.setNotifyVerified(notifyVerified);
            }
            if (order.getNotifyRaw() == null) {
                order.setNotifyRaw(rawPayload);
            }
            payOrderRepository.save(order);
            return;
        }
        order.setStatus(PayOrder.STATUS_PAID);
        if (!isBlank(transactionId)) {
            order.setTransactionId(transactionId);
        }
        if (!isBlank(openid)) {
            order.setOpenid(openid);
        }
        order.setNotifyType(notifyType);
        order.setNotifyVerified(notifyVerified);
        order.setNotifyRaw(rawPayload);
        order.setPaidAt(LocalDateTime.now());
        ensureTokenGenerated(order);
        payOrderRepository.save(order);
        referralService.recordPremiumPayment(order);
    }

    private void recordNotifyOnly(String outTradeNo, String notifyType, boolean verified, String rawPayload) {
        if (isBlank(outTradeNo)) {
            return;
        }
        payOrderRepository.findByOutTradeNo(outTradeNo).ifPresent(order -> {
            order.setNotifyType(notifyType);
            order.setNotifyVerified(verified);
            order.setNotifyRaw(rawPayload);
            payOrderRepository.save(order);
        });
    }

    private String normalizeChannel(String channel) {
        if ("alipay".equalsIgnoreCase(channel)) {
            return "alipay";
        }
        return "wechat";
    }

    private String normalizeScene(String scene, String channel) {
        if (!isBlank(scene)) {
            return scene;
        }
        return "alipay".equals(channel) ? "alipay_wap" : "wechat_h5";
    }

    private String normalizePendingStatus(PayOrder order) {
        if (PayOrder.STATUS_CREATED.equalsIgnoreCase(order.getStatus())) {
            return "CREATED";
        }
        return order.getStatus() == null ? "CREATED" : order.getStatus();
    }

    private void ensureTokenGenerated(PayOrder order) {
        if (order.getAccessToken() == null || order.getAccessToken().isBlank()) {
            order.setAccessToken(generateToken());
        }
    }

    private String extractClientIp(PaymentCreateOrderRequest request) {
        if (request.getClientContext() == null) {
            return null;
        }
        return "127.0.0.1";
    }

    private Map<String, Object> extractWechatResource(Map<String, Object> payload) {
        Object resource = payload.get("resource");
        if (resource instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            return casted;
        }
        return payload;
    }

    private String extractWechatOpenid(Map<String, Object> payload) {
        Object payer = payload.get("payer");
        if (payer instanceof Map<?, ?> map) {
            Object openid = map.get("openid");
            return openid == null ? null : String.valueOf(openid);
        }
        return null;
    }

    private String rawJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return String.valueOf(payload);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String httpPostForm(String url, Map<String, Object> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!body.isEmpty()) body.append("&");
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        java.net.http.HttpResponse<String> response = client.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private String generateRandom(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
