package com.zodiac.api.service;

import com.zodiac.api.config.AlipayConfig;
import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AlipayService {

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

        order.setTradeType(PayOrder.TRADE_TYPE_WAP);
        String payUrl = "https://openapi.alipay.com/gateway.do?mockWapPay=1&out_trade_no=" + order.getOutTradeNo();
        String formHtml = "<form id=\"alipay-submit\" action=\"" + payUrl + "\" method=\"GET\"></form>";
        order.setAlipayFormHtml(formHtml);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payUrl", payUrl);
        payload.put("formHtml", formHtml);
        payload.put("returnUrl", order.getReturnUrl() == null || order.getReturnUrl().isBlank()
                ? config.getReturnUrl()
                : order.getReturnUrl());
        payload.put("notifyUrl", config.getNotifyUrl());
        payload.put("signType", config.getSignType());
        payload.put("mode", "WAP");
        payload.put("enabled", true);
        return payload;
    }

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
