package com.zodiac.api.service;

import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.config.WechatPayConfig;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WechatPayService {

    private final WechatPayConfig config;
    private final PaymentProperties paymentProperties;

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        if (!config.isEnabled()) {
            throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ensureConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        String scene = normalizeScene(request.getScene());
        if ("wechat_jsapi".equals(scene)) {
            if (order.getOpenid() == null || order.getOpenid().isBlank()) {
                throw new PaymentException("wechat_openid_required", "微信内支付缺少 openid", HttpStatus.BAD_REQUEST);
            }
            String prepayId = "mock_prepay_" + order.getOutTradeNo();
            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
            order.setWechatPrepayId(prepayId);
            payload.put("appId", config.getAppId());
            payload.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
            payload.put("nonceStr", order.getOutTradeNo().substring(Math.max(0, order.getOutTradeNo().length() - 16)));
            payload.put("package", "prepay_id=" + prepayId);
            payload.put("signType", "RSA");
            payload.put("paySign", "MOCK_WECHAT_PAY_SIGN");
            payload.put("mode", "JSAPI");
        } else if ("wechat_h5".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_H5);
            String returnUrl = order.getReturnUrl() == null || order.getReturnUrl().isBlank()
                    ? config.getH5ReturnUrl()
                    : order.getReturnUrl();
            String mwebUrl = "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id="
                    + order.getOutTradeNo()
                    + "&redirect_url=" + urlEncode(returnUrl);
            order.setWechatMwebUrl(mwebUrl);
            payload.put("mwebUrl", mwebUrl);
            payload.put("mode", "H5");
        } else {
            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
            String codeUrl = "weixin://wxpay/bizpayurl?pr=" + order.getOutTradeNo();
            order.setWechatCodeUrl(codeUrl);
            payload.put("codeUrl", codeUrl);
            payload.put("mode", "NATIVE");
            payload.put("fallbackQrEnabled", paymentProperties.isFallbackQrEnabled());
        }

        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        payload.put("notifyUrl", config.getNotifyUrl());
        payload.put("enabled", true);
        return payload;
    }

    public String exchangeOpenid(String code) {
        if (!config.isEnabled()) {
            throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ensureConfigured();
        if (code == null || code.isBlank()) {
            throw new PaymentException("wechat_code_required", "微信授权 code 不能为空");
        }
        return "mock-openid-" + Math.abs(code.hashCode());
    }

    private void ensureConfigured() {
        if (isBlank(config.getMchId())
                || isBlank(config.getAppId())
                || isBlank(config.getApiV3Key())
                || isBlank(config.getNotifyUrl())) {
            throw new PaymentException(
                    "wechat_config_missing",
                    "微信支付配置不完整，请检查 WECHAT_PAY_MCH_ID / WECHAT_PAY_APP_ID / WECHAT_PAY_API_V3_KEY / WECHAT_PAY_NOTIFY_URL",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private String normalizeScene(String scene) {
        if ("wechat_jsapi".equalsIgnoreCase(scene)) {
            return "wechat_jsapi";
        }
        if ("wechat_h5".equalsIgnoreCase(scene)) {
            return "wechat_h5";
        }
        return "wechat_native";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
