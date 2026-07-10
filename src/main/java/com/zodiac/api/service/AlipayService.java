package com.zodiac.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.config.AlipayConfig;
import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlipayService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlipayConfig config;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        if (!config.isEnabled()) {
            if (!paymentProperties.isDevMockEnabled()) {
                throw new PaymentException("alipay_disabled", "支付宝支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return buildMockPayload(order);
        }
        ensureConfigured();
        try {
            Map<String, String> params = buildCommonParams("alipay.trade.wap.pay");
            params.put("return_url", firstNonBlank(order.getReturnUrl(), config.getReturnUrl()));
            params.put("notify_url", config.getNotifyUrl());
            Map<String, Object> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", order.getOutTradeNo());
            bizContent.put("total_amount", formatYuan(order.getAmountFen()));
            bizContent.put("subject", order.getSubject());
            bizContent.put("product_code", "QUICK_WAP_WAY");
            bizContent.put("body", firstNonBlank(order.getReportType(), "premium_report"));
            bizContent.put("timeout_express", paymentProperties.getOrderExpireMinutes() + "m");
            params.put("biz_content", objectMapper.writeValueAsString(bizContent));
            params.put("sign", sign(params, loadPrivateKey()));

            String formHtml = buildAutoSubmitForm(params);
            String payUrl = config.getGatewayUrl() + "?" + encodeParams(params);
            order.setTradeType("WAP");
            order.setAlipayPayUrl(payUrl);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("formHtml", formHtml);
            payload.put("payUrl", payUrl);
            payload.put("mode", "WAP");
            payload.put("enabled", true);
            payload.put("gatewayUrl", config.getGatewayUrl());
            payload.put("notifyUrl", config.getNotifyUrl());
            payload.put("returnUrl", params.get("return_url"));
            payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
            return payload;
        } catch (PaymentException ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Alipay fallback to mock payload: outTradeNo={}, errorCode={}", order.getOutTradeNo(), ex.getErrorCode());
                return buildMockPayload(order);
            }
            throw ex;
        } catch (Exception ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Alipay fallback to mock payload after exception: outTradeNo={}", order.getOutTradeNo(), ex);
                return buildMockPayload(order);
            }
            throw new PaymentException("alipay_order_create_failed", "支付宝下单失败，请检查应用私钥和回调地址", HttpStatus.BAD_GATEWAY);
        }
    }

    public boolean verifyNotify(Map<String, String> params) {
        if (!config.isEnabled() || isBlank(config.getPublicKey())) {
            return false;
        }
        String sign = params.get("sign");
        if (isBlank(sign)) {
            return false;
        }
        try {
            Map<String, String> unsigned = new TreeMap<>(params);
            unsigned.remove("sign");
            unsigned.remove("sign_type");
            String content = canonicalize(unsigned);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadAlipayPublicKey());
            verifier.update(content.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            log.warn("Alipay notify verify failed: outTradeNo={}", params.get("out_trade_no"), ex);
            return false;
        }
    }

    private Map<String, Object> buildMockPayload(PayOrder order) {
        order.setTradeType("WAP");
        String returnUrl = firstNonBlank(order.getReturnUrl(), config.getReturnUrl(), "http://127.0.0.1:3000/?payReturn=1");
        order.setAlipayPayUrl(returnUrl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payUrl", returnUrl);
        payload.put("formHtml", "");
        payload.put("mode", "WAP");
        payload.put("enabled", false);
        payload.put("mock", true);
        payload.put("mockHint", "当前为本地模拟支付，可在后台补单或调用 dev/mark-paid 完成测试。");
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    private Map<String, String> buildCommonParams(String method) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", config.getAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", firstNonBlank(config.getSignType(), "RSA2"));
        params.put("timestamp", java.time.LocalDateTime.now().format(TIME_FORMAT));
        params.put("version", "1.0");
        return params;
    }

    private String buildAutoSubmitForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("<form id=\"alipay-submit\" name=\"alipay-submit\" action=\"")
                .append(escapeHtml(config.getGatewayUrl()))
                .append("\" method=\"POST\">");
        params.forEach((key, value) -> sb.append("<input type=\"hidden\" name=\"")
                .append(escapeHtml(key))
                .append("\" value=\"")
                .append(escapeHtml(value))
                .append("\"/>"));
        sb.append("</form><script>document.forms['alipay-submit'].submit();</script>");
        return sb.toString();
    }

    private PrivateKey loadPrivateKey() {
        String pem = config.getPrivateKey();
        if (isBlank(pem) && !isBlank(config.getPrivateKeyPath())) {
            try {
                pem = Files.readString(Path.of(config.getPrivateKeyPath()), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new PaymentException("alipay_private_key_load_failed", "支付宝应用私钥加载失败", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
        if (isBlank(pem)) {
            throw new PaymentException("alipay_private_key_missing", "缺少支付宝应用私钥", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            String normalized = stripPem(pem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized));
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception ex) {
            throw new PaymentException("alipay_private_key_load_failed", "支付宝应用私钥加载失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private java.security.PublicKey loadAlipayPublicKey() throws Exception {
        String normalized = stripPem(config.getPublicKey());
        return KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
    }

    private String sign(Map<String, String> params, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(canonicalize(params).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private String canonicalize(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            if (isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private String encodeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(urlEncode(key)).append("=").append(urlEncode(value));
        });
        return sb.toString();
    }

    private String formatYuan(Integer amountFen) {
        return BigDecimal.valueOf(amountFen == null ? 0 : amountFen)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private void ensureConfigured() {
        if (isBlank(config.getAppId())
                || (isBlank(config.getPrivateKey()) && isBlank(config.getPrivateKeyPath()))
                || isBlank(config.getNotifyUrl())
                || isBlank(config.getGatewayUrl())) {
            throw new PaymentException("alipay_config_missing", "支付宝支付配置不完整，请检查 APP_ID / PRIVATE_KEY / NOTIFY_URL / GATEWAY_URL", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String stripPem(String pem) {
        return pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String escapeHtml(String value) {
        return String.valueOf(value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
