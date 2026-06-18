package com.zodiac.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.config.WechatPayConfig;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPayService {

    private static final String WECHAT_API_HOST = "https://api.mch.weixin.qq.com";
    private static final String JSAPI_PATH = "/v3/pay/transactions/jsapi";
    private static final String H5_PATH = "/v3/pay/transactions/h5";
    private static final String NATIVE_PATH = "/v3/pay/transactions/native";
    private static final String OAUTH_PATH = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String OAUTH_OPENID_PATH = "https://api.weixin.qq.com/sns/jscode2session";

    private final WechatPayConfig config;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        if (!config.isEnabled()) {
            if (!paymentProperties.isDevMockEnabled()) {
                throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return buildMockPayload(order, request);
        }
        ensureConfigured();

        String scene = normalizeScene(request.getScene());
        return callWechatPayApi(order, request, scene);
    }

    public String exchangeOpenid(String code) {
        if (!config.isEnabled()) {
            throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ensureConfigured();
        if (code == null || code.isBlank()) {
            throw new PaymentException("wechat_code_required", "微信授权 code 不能为空");
        }
        return exchangeOpenidViaApi(code);
    }

    // ==================== Real API ====================

    private Map<String, Object> callWechatPayApi(PayOrder order, PaymentCreateOrderRequest request, String scene) {
        try {
            String path;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("appid", config.getAppId());
            body.put("mchid", config.getMchId());
            body.put("description", resolveSubject(request));
            body.put("out_trade_no", order.getOutTradeNo());
            body.put("notify_url", config.getNotifyUrl());

            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put("total", order.getAmountFen());
            amount.put("currency", "CNY");
            body.put("amount", amount);

            if ("wechat_jsapi".equals(scene)) {
                path = JSAPI_PATH;
                if (order.getOpenid() == null || order.getOpenid().isBlank()) {
                    throw new PaymentException("wechat_openid_required", "微信内支付缺少 openid", HttpStatus.BAD_REQUEST);
                }
                Map<String, String> payer = new LinkedHashMap<>();
                payer.put("openid", order.getOpenid());
                body.put("payer", payer);
            } else if ("wechat_h5".equals(scene)) {
                path = H5_PATH;
                Map<String, Object> sceneInfo = new LinkedHashMap<>();
                Map<String, String> h5Info = new LinkedHashMap<>();
                h5Info.put("type", "Wap");
                h5Info.put("wap_url", blankToNull(order.getReturnUrl()) != null ? order.getReturnUrl() : config.getH5ReturnUrl());
                h5Info.put("wap_name", "小登哥星盘");
                sceneInfo.put("h5_info", h5Info);
                sceneInfo.put("payer_client_ip", blankToNull(order.getClientIp()) != null ? order.getClientIp() : "127.0.0.1");
                body.put("scene_info", sceneInfo);
            } else {
                path = NATIVE_PATH;
            }

            String bodyJson = objectMapper.writeValueAsString(body);
            String fullUrl = WECHAT_API_HOST + path;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", buildWechatAuthHeader("POST", path, bodyJson))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode respJson = objectMapper.readTree(response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return buildSuccessPayload(order, scene, respJson);
            } else {
                String errMsg = respJson.has("message") ? respJson.get("message").asText() : response.body();
                log.error("Wechat pay API error: status={}, body={}", response.statusCode(), response.body());
                throw new PaymentException("wechat_api_error", "微信支付下单失败: " + errMsg, HttpStatus.BAD_GATEWAY);
            }
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Wechat pay API call failed", e);
            throw new PaymentException("wechat_pay_error", "微信支付系统异常，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private Map<String, Object> buildSuccessPayload(PayOrder order, String scene, JsonNode respJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String prepayId = respJson.has("prepay_id") ? respJson.get("prepay_id").asText() : "";

        if ("wechat_jsapi".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
            order.setWechatPrepayId(prepayId);

            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            long timestamp = Instant.now().getEpochSecond();
            String packageStr = "prepay_id=" + prepayId;

            // Generate paySign for JSAPI
            String signStr = config.getAppId() + "\n" + timestamp + "\n" + nonceStr + "\n" + packageStr + "\n";
            String paySign = signWithPrivateKey(signStr);

            payload.put("appId", config.getAppId());
            payload.put("timeStamp", String.valueOf(timestamp));
            payload.put("nonceStr", nonceStr);
            payload.put("package", packageStr);
            payload.put("signType", "RSA");
            payload.put("paySign", paySign);
            payload.put("mode", "JSAPI");
        } else if ("wechat_h5".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_H5);
            String h5Url = respJson.has("h5_url") ? respJson.get("h5_url").asText() : "";
            order.setWechatMwebUrl(h5Url);
            payload.put("mwebUrl", h5Url);
            payload.put("mode", "H5");
        } else {
            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
            String codeUrl = respJson.has("code_url") ? respJson.get("code_url").asText() : "";
            order.setWechatCodeUrl(codeUrl);
            payload.put("codeUrl", codeUrl);
            payload.put("mode", "NATIVE");
        }

        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        payload.put("enabled", true);
        return payload;
    }

    private String exchangeOpenidViaApi(String code) {
        try {
            String appId = config.getAppId();
            String appSecret = config.getAppSecret();
            if (isBlank(appSecret)) {
                throw new PaymentException("wechat_app_secret_missing", "微信 AppSecret 未配置，无法获取 openid", HttpStatus.SERVICE_UNAVAILABLE);
            }

            String url = OAUTH_OPENID_PATH + "?appid=" + urlEncode(appId)
                    + "&secret=" + urlEncode(appSecret)
                    + "&js_code=" + urlEncode(code)
                    + "&grant_type=authorization_code";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            if (json.has("openid")) {
                return json.get("openid").asText();
            }
            String errMsg = json.has("errmsg") ? json.get("errmsg").asText("未知错误") : "未知错误";
            throw new PaymentException("wechat_openid_failed", "获取微信 openid 失败: " + errMsg);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exchange openid failed", e);
            throw new PaymentException("wechat_openid_error", "微信 openid 获取失败");
        }
    }

    // ==================== Signing ====================

    private String buildWechatAuthHeader(String method, String path, String body) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            String message = method + "\n" + path + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
            String signature = signWithPrivateKey(message);

            return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + config.getMchId()
                    + "\",nonce_str=\"" + nonceStr
                    + "\",signature=\"" + signature
                    + "\",timestamp=\"" + timestamp
                    + "\",serial_no=\"" + (blankToNull(config.getMchSerialNo()) != null ? config.getMchSerialNo() : "") + "\"";
        } catch (Exception e) {
            throw new PaymentException("wechat_sign_error", "微信支付签名失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String signWithPrivateKey(String message) throws Exception {
        String privateKeyPath = config.getPrivateKeyPath();
        if (isBlank(privateKeyPath)) {
            throw new PaymentException("wechat_private_key_missing", "微信支付私钥路径未配置");
        }
        byte[] keyBytes = Files.readAllBytes(Path.of(privateKeyPath));
        String keyContent = new String(keyBytes).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        Signature sign = Signature.getInstance("SHA256withRSA");
        sign.initSign(kf.generatePrivate(spec));
        sign.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sign.sign());
    }

    // ==================== Mock ====================

    private Map<String, Object> buildMockPayload(PayOrder order, PaymentCreateOrderRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String scene = normalizeScene(request.getScene());
        if ("wechat_jsapi".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
            order.setWechatPrepayId("mock_prepay_" + order.getOutTradeNo());
            payload.put("appId", "mock-wechat-app");
            payload.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
            payload.put("nonceStr", "mocknonce");
            payload.put("package", "prepay_id=" + order.getWechatPrepayId());
            payload.put("signType", "RSA");
            payload.put("paySign", "MOCK_PAY_SIGN");
            payload.put("mode", "JSAPI");
        } else if ("wechat_h5".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_H5);
            String mwebUrl = (order.getReturnUrl() == null || order.getReturnUrl().isBlank())
                    ? "http://127.0.0.1:5173/?mock_wechat_h5=1"
                    : order.getReturnUrl();
            order.setWechatMwebUrl(mwebUrl);
            payload.put("mwebUrl", mwebUrl);
            payload.put("mode", "H5");
        } else {
            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
            String codeUrl = "mock-wechat://" + order.getOutTradeNo();
            order.setWechatCodeUrl(codeUrl);
            payload.put("codeUrl", codeUrl);
            payload.put("mode", "NATIVE");
        }
        payload.put("enabled", false);
        payload.put("mock", true);
        payload.put("mockHint", "当前为本地开发模拟支付，可在后台补单或调用 dev/mark-paid 完成测试。");
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    // ==================== Helpers ====================

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

    private String resolveSubject(PaymentCreateOrderRequest request) {
        if (request.getSubject() != null && !request.getSubject().isBlank()) {
            return request.getSubject().trim();
        }
        return "深度解析服务";
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
