package com.zodiac.api.service;

import com.zodiac.api.config.AlipayConfig;
import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlipayService {

    private static final String ALIPAY_GATEWAY = "https://openapi.alipay.com/gateway.do";

    private final AlipayConfig config;
    private final PaymentProperties paymentProperties;

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        if (!config.isEnabled()) {
            if (!paymentProperties.isDevMockEnabled()) {
                throw new PaymentException("alipay_disabled", "支付宝支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return buildMockPayload(order);
        }
        ensureConfigured();

        return buildRealPayPayload(order, request);
    }

    // ==================== Real API ====================

    private Map<String, Object> buildRealPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        try {
            order.setTradeType(PayOrder.TRADE_TYPE_WAP);

            // Build biz content JSON
            Map<String, Object> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", order.getOutTradeNo());
            bizContent.put("total_amount", fenToYuan(order.getAmountFen()));
            bizContent.put("subject", blankToNull(request.getSubject()) != null ? request.getSubject() : "深度解析服务");
            bizContent.put("product_code", "QUICK_WAP_WAY");
            bizContent.put("quit_url", blankToNull(order.getReturnUrl()) != null ? order.getReturnUrl() : config.getReturnUrl());

            // Build request params (sorted by key for signing)
            TreeMap<String, String> params = new TreeMap<>();
            params.put("app_id", config.getAppId());
            params.put("method", "alipay.trade.wap.pay");
            params.put("charset", "utf-8");
            params.put("sign_type", config.getSignType());
            params.put("timestamp", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.LocalDateTime.now()));
            params.put("version", "1.0");
            params.put("notify_url", config.getNotifyUrl());
            params.put("return_url", config.getReturnUrl());
            params.put("biz_content", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(bizContent));

            // Build query string for signing
            StringBuilder signStr = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (signStr.length() > 0) signStr.append("&");
                signStr.append(entry.getKey()).append("=").append(entry.getValue());
            }
            params.put("sign", signWithPrivateKey(signStr.toString()));

            // Build final URL
            StringBuilder payUrl = new StringBuilder(ALIPAY_GATEWAY).append("?");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!payUrl.toString().endsWith("?")) payUrl.append("&");
                payUrl.append(urlEncode(entry.getKey())).append("=").append(urlEncode(entry.getValue()));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("payUrl", payUrl.toString());
            payload.put("returnUrl", config.getReturnUrl());
            payload.put("notifyUrl", config.getNotifyUrl());
            payload.put("signType", config.getSignType());
            payload.put("mode", "WAP");
            payload.put("enabled", true);
            return payload;

        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Alipay pay build failed", e);
            throw new PaymentException("alipay_pay_error", "支付宝支付系统异常，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String signWithPrivateKey(String content) throws Exception {
        String privateKeyPath = config.getPrivateKeyPath();
        if (isBlank(privateKeyPath)) {
            throw new PaymentException("alipay_private_key_missing", "支付宝私钥路径未配置");
        }
        byte[] keyBytes = Files.readAllBytes(Path.of(privateKeyPath));
        String keyContent = new String(keyBytes).replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");

        Signature sign = Signature.getInstance("RSA2".equals(config.getSignType())
                ? "SHA256withRSA" : "SHA1withRSA");
        sign.initSign(kf.generatePrivate(spec));
        sign.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sign.sign());
    }

    // ==================== Mock ====================

    private Map<String, Object> buildMockPayload(PayOrder order) {
        order.setTradeType(PayOrder.TRADE_TYPE_WAP);
        String payUrl = (order.getReturnUrl() == null || order.getReturnUrl().isBlank())
                ? "http://127.0.0.1:5173/?mock_alipay=1"
                : order.getReturnUrl();
        order.setAlipayFormHtml("<form id=\"alipay-submit\" action=\"" + payUrl + "\" method=\"GET\"></form>");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payUrl", payUrl);
        payload.put("formHtml", order.getAlipayFormHtml());
        payload.put("mode", "WAP");
        payload.put("enabled", false);
        payload.put("mock", true);
        payload.put("mockHint", "当前为本地开发模拟支付，可在后台补单或调用 dev/mark-paid 完成测试。");
        return payload;
    }

    // ==================== Helpers ====================

    private void ensureConfigured() {
        if (isBlank(config.getAppId())
                || isBlank(config.getNotifyUrl())
                || isBlank(config.getReturnUrl())
                || isBlank(config.getPublicKey())) {
            throw new PaymentException(
                    "alipay_config_missing",
                    "支付宝配置不完整，请检查 ALIPAY_APP_ID / ALIPAY_NOTIFY_URL / ALIPAY_RETURN_URL / ALIPAY_PUBLIC_KEY",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private String fenToYuan(Integer fen) {
        if (fen == null || fen <= 0) return "0.00";
        return String.format("%.2f", fen / 100.0);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
