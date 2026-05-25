package com.zodiac.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.config.PayJsConfig;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.util.PayJsSignUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayService {

    private static final String PAYJS_NATIVE_URL = "https://payjs.cn/api/native";
    private static final String PAYJS_CHECK_URL  = "https://payjs.cn/api/check";
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOTAL_FEE = 1990; // 19.90 yuan
    private static final SecureRandom RNG = new SecureRandom();

    private final PayJsConfig config;
    private final PayOrderRepository payOrderRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建支付订单，返回二维码地址和订单号 */
    public Map<String, Object> createOrder() {
        String outTradeNo = "ZD" + generateRandom(12);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mchid",         config.getMchid());
        params.put("total_fee",     TOTAL_FEE);
        params.put("out_trade_no",  outTradeNo);
        params.put("body",          "星座深度合盘解析 · Claude 深度版");
        if (config.getNotifyUrl() != null && !config.getNotifyUrl().isBlank()) {
            params.put("notify_url", config.getNotifyUrl());
        }
        params.put("sign", PayJsSignUtil.sign(params, config.getKey()));

        String responseBody;
        try {
            responseBody = httpPostForm(PAYJS_NATIVE_URL, params);
        } catch (Exception e) {
            log.error("PayJS native 下单失败: {}", e.getMessage(), e);
            throw new RuntimeException("支付服务暂时不可用，请稍后重试");
        }

        Map<String, Object> result;
        try {
            //noinspection unchecked
            result = objectMapper.readValue(responseBody, Map.class);
        } catch (Exception e) {
            log.error("PayJS 返回解析失败: {}", responseBody, e);
            throw new RuntimeException("支付服务返回异常");
        }

        int returnCode = toInt(result.get("return_code"));
        if (returnCode != 1) {
            String msg = String.valueOf(result.getOrDefault("return_msg", "未知错误"));
            log.error("PayJS 下单失败: return_code={}, msg={}", returnCode, msg);
            throw new RuntimeException("支付下单失败: " + msg);
        }

        PayOrder order = new PayOrder();
        order.setOutTradeNo(outTradeNo);
        order.setPayjsOrderId(String.valueOf(result.get("payjs_order_id")));
        order.setTotalFee(TOTAL_FEE);
        order.setStatus(PayOrder.STATUS_CREATED);
        payOrderRepository.save(order);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo",   outTradeNo);
        resp.put("payjsOrderId", result.get("payjs_order_id"));
        resp.put("qrcode",       result.get("qrcode"));
        resp.put("codeUrl",      result.get("code_url"));
        resp.put("totalFee",     TOTAL_FEE);
        return resp;
    }

    /** 处理 PayJS 异步回调 */
    public String handleNotify(Map<String, String> params) {
        log.info("收到 PayJS 回调: out_trade_no={}, return_code={}",
                params.get("out_trade_no"), params.get("return_code"));

        if (!PayJsSignUtil.verifySign(params, config.getKey())) {
            log.warn("PayJS 回调验签失败: out_trade_no={}", params.get("out_trade_no"));
            return "";
        }

        String returnCode = params.get("return_code");
        if (!"1".equals(returnCode)) {
            log.warn("PayJS 回调支付未成功: out_trade_no={}, return_code={}",
                    params.get("out_trade_no"), returnCode);
            return "";
        }

        String outTradeNo = params.get("out_trade_no");
        payOrderRepository.findByOutTradeNo(outTradeNo).ifPresentOrElse(order -> {
            if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
                log.info("订单已处理，跳过: {}", outTradeNo);
                return;
            }
            order.setStatus(PayOrder.STATUS_PAID);
            order.setTransactionId(params.get("transaction_id"));
            order.setOpenid(params.get("openid"));
            order.setPaidAt(LocalDateTime.now());
            if (order.getAccessToken() == null) {
                order.setAccessToken(generateToken());
            }
            payOrderRepository.save(order);
            log.info("订单支付成功: out_trade_no={}", outTradeNo);
        }, () -> log.warn("回调订单不存在: {}", outTradeNo));

        return "success";
    }

    /**
     * 查询支付状态（前端轮询用）。
     * 先查本地，本地未支付则调 PayJS check 接口同步。
     * 已支付时一并返回 accessToken 给前端。
     */
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
            resp.put("status", "PAID");
            resp.put("accessToken", order.getAccessToken());
            return resp;
        }

        try {
            Map<String, Object> checkParams = new LinkedHashMap<>();
            checkParams.put("payjs_order_id", order.getPayjsOrderId());
            checkParams.put("sign", PayJsSignUtil.sign(checkParams, config.getKey()));

            String body = httpPostForm(PAYJS_CHECK_URL, checkParams);
            //noinspection unchecked
            Map<String, Object> checkResult = objectMapper.readValue(body, Map.class);

            int status = toInt(checkResult.get("status"));
            if (status == 1) {
                order.setStatus(PayOrder.STATUS_PAID);
                String txId = String.valueOf(checkResult.getOrDefault("transaction_id", ""));
                if (!txId.isBlank()) {
                    order.setTransactionId(txId);
                }
                order.setPaidAt(LocalDateTime.now());
                ensureTokenGenerated(order);
                payOrderRepository.save(order);
                resp.put("status", "PAID");
                resp.put("accessToken", order.getAccessToken());
            } else {
                resp.put("status", "CREATED");
            }
        } catch (Exception e) {
            log.warn("PayJS check 接口调用异常: out_trade_no={}, {}", outTradeNo, e.getMessage());
            resp.put("status", PayOrder.STATUS_CREATED);
        }
        return resp;
    }

    /**
     * 验证并消耗 accessToken（一次性）。
     * 供 CompatibilityController 在生成 claude 报告前调用：验证通过立即消耗（防重放）。
     */
    public boolean consumeToken(String token) {
        if (token == null || token.isBlank()) return false;
        Optional<PayOrder> opt = payOrderRepository.findByAccessToken(token);
        if (opt.isEmpty()) return false;

        PayOrder order = opt.get();
        if (!PayOrder.STATUS_PAID.equals(order.getStatus())) return false;
        if (order.getTokenConsumedAt() != null) return false;
        if (order.getPaidAt() != null &&
                order.getPaidAt().plusHours(PayOrder.TOKEN_EXPIRE_HOURS).isBefore(LocalDateTime.now())) {
            log.warn("accessToken 已过期: token={}", token);
            return false;
        }
        order.setTokenConsumedAt(LocalDateTime.now());
        payOrderRepository.save(order);
        log.info("accessToken 已消耗: out_trade_no={}", order.getOutTradeNo());
        return true;
    }

    /** 仅校验 token 是否有效（不消耗），用于前端预检 */
    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) return false;
        Optional<PayOrder> opt = payOrderRepository.findByAccessToken(token);
        if (opt.isEmpty()) return false;
        PayOrder order = opt.get();
        if (!PayOrder.STATUS_PAID.equals(order.getStatus())) return false;
        if (order.getTokenConsumedAt() != null) return false;
        if (order.getPaidAt() != null &&
                order.getPaidAt().plusHours(PayOrder.TOKEN_EXPIRE_HOURS).isBefore(LocalDateTime.now())) {
            return false;
        }
        return true;
    }

    // ========== 工具方法 ==========

    /**
     * 手动创建订单（不走 PayJS，直接本地创建）。
     * 用于静态收款码 + 后台手动确认模式。
     */
    public Map<String, Object> createManualOrder() {
        String outTradeNo = "ZD" + generateRandom(12);

        PayOrder order = new PayOrder();
        order.setOutTradeNo(outTradeNo);
        order.setTotalFee(TOTAL_FEE);
        order.setStatus(PayOrder.STATUS_CREATED);
        payOrderRepository.save(order);

        log.info("手动订单已创建: out_trade_no={}", outTradeNo);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("outTradeNo", outTradeNo);
        resp.put("totalFee", TOTAL_FEE);
        resp.put("status", PayOrder.STATUS_CREATED);
        return resp;
    }

    /**
     * 手动确认支付（管理员在后台操作）。
     * 不走 PayJS，直接将订单状态改为 PAID 并生成 accessToken。
     */
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
            return resp;
        }

        order.setStatus(PayOrder.STATUS_PAID);
        order.setPaidAt(LocalDateTime.now());
        ensureTokenGenerated(order);
        payOrderRepository.save(order);

        log.info("手动确认支付成功: out_trade_no={}", outTradeNo);

        resp.put("success", true);
        resp.put("message", "确认成功");
        resp.put("accessToken", order.getAccessToken());
        return resp;
    }

    private void ensureTokenGenerated(PayOrder order) {
        if (order.getAccessToken() == null) {
            order.setAccessToken(generateToken());
        }
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
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!body.isEmpty()) body.append("&");
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                .append("=")
                .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

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
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
