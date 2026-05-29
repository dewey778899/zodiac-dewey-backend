package com.zodiac.api.service;

import com.zodiac.api.config.AlipayConfig;
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

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        if (!config.isEnabled()) {
            throw new PaymentException("alipay_disabled", "支付宝支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
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
