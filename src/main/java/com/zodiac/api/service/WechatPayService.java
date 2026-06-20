package com.zodiac.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.config.WechatPayConfig;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPayService {

    public static final String API_BASE_URL = "https://api.mch.weixin.qq.com";
    public static final String API_BACKUP_BASE_URL = "https://api2.mch.weixin.qq.com";
    public static final String H5_ORDER_PATH = "/v3/pay/transactions/h5";
    public static final String JSAPI_ORDER_PATH = "/v3/pay/transactions/jsapi";
    public static final String NATIVE_ORDER_PATH = "/v3/pay/transactions/native";
    public static final String QUERY_ORDER_PATH_TEMPLATE = "/v3/pay/transactions/out-trade-no/{out_trade_no}?mchid={mchid}";
    public static final String CERTIFICATES_PATH = "/v3/certificates";
    public static final String AUTH_SCHEME = "WECHATPAY2-SHA256-RSA2048";
    private static final String USER_AGENT = "zodiac-dewey/1.0";

    private final WechatPayConfig config;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
        String scene = normalizeScene(request.getScene());
        if (!config.isEnabled()) {
            if (!paymentProperties.isDevMockEnabled()) {
                throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return buildMockPayload(order, request, scene);
        }

        ensureConfigured();
        try {
            if ("wechat_jsapi".equals(scene)) {
                return createJsapiOrder(order, request);
            }
            if ("wechat_h5".equals(scene)) {
                return createH5Order(order, request);
            }
            return createNativeOrder(order, request);
        } catch (PaymentException ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Wechat pay fallback to mock payload: outTradeNo={}, scene={}, errorCode={}", order.getOutTradeNo(), scene, ex.getErrorCode());
                return buildMockPayload(order, request, scene);
            }
            throw ex;
        } catch (Exception ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Wechat pay fallback to mock payload after exception: outTradeNo={}, scene={}", order.getOutTradeNo(), scene, ex);
                return buildMockPayload(order, request, scene);
            }
            log.error("Wechat pay create order failed: outTradeNo={}, scene={}", order.getOutTradeNo(), scene, ex);
            throw new PaymentException("wechat_order_create_failed", "微信支付下单失败，请检查商户参数、证书和回调地址", HttpStatus.BAD_GATEWAY);
        }
    }

    public String exchangeOpenid(String code) {
        if (!config.isEnabled()) {
            throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ensureConfigured();
        if (code == null || code.isBlank()) {
            throw new PaymentException("wechat_code_required", "微信授权 code 不能为空", HttpStatus.BAD_REQUEST);
        }
        if (isBlank(config.getAppSecret())) {
            throw new PaymentException("wechat_app_secret_missing", "缺少 WECHAT_PAY_APP_SECRET，暂不能用 code 换取 openid", HttpStatus.SERVICE_UNAVAILABLE);
        }

        try {
            Map<String, Object> resp = WebClient.builder()
                    .baseUrl("https://api.weixin.qq.com")
                    .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/sns/jscode2session")
                            .queryParam("appid", config.getAppId())
                            .queryParam("secret", config.getAppSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            if (resp == null) {
                throw new PaymentException("wechat_openid_empty", "微信 openid 获取失败", HttpStatus.BAD_GATEWAY);
            }
            String openid = asString(resp.get("openid"));
            if (!isBlank(openid)) {
                return openid;
            }
            throw new PaymentException("wechat_openid_empty", "微信 openid 获取失败: " + resp, HttpStatus.BAD_GATEWAY);
        } catch (PaymentException ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Wechat openid exchange fallback to mock openid: code={}, errorCode={}", code, ex.getErrorCode());
                return "mock-openid-" + code;
            }
            throw ex;
        } catch (Exception ex) {
            if (paymentProperties.isDevMockEnabled()) {
                log.warn("Wechat openid exchange fallback to mock openid after exception: code={}", code, ex);
                return "mock-openid-" + code;
            }
            log.error("Wechat exchange openid failed: code={}", code, ex);
            throw new PaymentException("wechat_openid_exchange_failed", "微信 openid 换取失败，请检查 AppSecret、code 和小程序配置", HttpStatus.BAD_GATEWAY);
        }
    }

    public boolean verifyCallback(String timestamp, String nonce, String body, String serial, String signature) {
        if (isBlank(timestamp) || isBlank(nonce) || body == null || isBlank(signature)) {
            return false;
        }
        try {
            X509Certificate certificate = loadPlatformCertificate(serial);
            String message = timestamp + "\n" + nonce + "\n" + body + "\n";
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(certificate);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception ex) {
            log.error("Wechat callback verify failed", ex);
            return false;
        }
    }

    public Map<String, Object> decryptCallbackResource(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object resourceObj = root.get("resource");
            if (!(resourceObj instanceof Map<?, ?> resource)) {
                return root;
            }
            String associatedData = asString(resource.get("associated_data"));
            String nonce = asString(resource.get("nonce"));
            String ciphertext = asString(resource.get("ciphertext"));
            if (isBlank(nonce) || isBlank(ciphertext)) {
                return root;
            }
            String plainText = decryptAesGcm(ciphertext, nonce, associatedData, config.getApiV3Key());
            Map<String, Object> decrypted = objectMapper.readValue(plainText, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> merged = new LinkedHashMap<>(root);
            merged.put("resource", decrypted);
            return merged;
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException("wechat_callback_decrypt_failed", "微信回调解密失败", HttpStatus.BAD_REQUEST);
        }
    }

    private Map<String, Object> createJsapiOrder(PayOrder order, PaymentCreateOrderRequest request) throws Exception {
        if (isBlank(order.getOpenid())) {
            throw new PaymentException("wechat_openid_required", "微信 JSAPI 支付缺少 openid", HttpStatus.BAD_REQUEST);
        }
        order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
        Map<String, Object> reqBody = buildCommonOrderBody(order, request);
        reqBody.put("payer", Map.of("openid", order.getOpenid()));
        Map<String, Object> response = postWechatApi(JSAPI_ORDER_PATH, reqBody);
        String prepayId = asString(response.get("prepay_id"));
        if (isBlank(prepayId)) {
            throw new PaymentException("wechat_prepay_missing", "微信 JSAPI 下单未返回 prepay_id", HttpStatus.BAD_GATEWAY);
        }
        order.setWechatPrepayId(prepayId);

        long timestamp = Instant.now().getEpochSecond();
        String nonceStr = randomNonce();
        String pkg = "prepay_id=" + prepayId;
        String message = config.getAppId() + "\n" + timestamp + "\n" + nonceStr + "\n" + pkg + "\n";
        String paySign = sign(message, loadMerchantPrivateKey());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", config.getAppId());
        payload.put("timeStamp", String.valueOf(timestamp));
        payload.put("nonceStr", nonceStr);
        payload.put("package", pkg);
        payload.put("signType", "RSA");
        payload.put("paySign", paySign);
        payload.put("mode", "JSAPI");
        appendOfficialApiMeta(payload, JSAPI_ORDER_PATH);
        payload.put("notifyUrl", config.getNotifyUrl());
        payload.put("enabled", true);
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    private Map<String, Object> createH5Order(PayOrder order, PaymentCreateOrderRequest request) throws Exception {
        order.setTradeType(PayOrder.TRADE_TYPE_H5);
        Map<String, Object> reqBody = buildCommonOrderBody(order, request);
        reqBody.put("scene_info", Map.of(
                "payer_client_ip", defaultClientIp(order.getClientIp()),
                "h5_info", Map.of("type", "Wap")
        ));
        Map<String, Object> response = postWechatApi(H5_ORDER_PATH, reqBody);
        String h5Url = asString(response.get("h5_url"));
        if (isBlank(h5Url)) {
            throw new PaymentException("wechat_h5_url_missing", "微信 H5 下单未返回 h5_url", HttpStatus.BAD_GATEWAY);
        }

        String returnUrl = isBlank(order.getReturnUrl()) ? config.getH5ReturnUrl() : order.getReturnUrl();
        String finalMwebUrl = h5Url;
        if (!isBlank(returnUrl)) {
            finalMwebUrl = h5Url + (h5Url.contains("?") ? "&" : "?") + "redirect_url=" + urlEncode(returnUrl);
        }
        order.setWechatMwebUrl(finalMwebUrl);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mwebUrl", finalMwebUrl);
        payload.put("mode", "H5");
        appendOfficialApiMeta(payload, H5_ORDER_PATH);
        payload.put("notifyUrl", config.getNotifyUrl());
        payload.put("enabled", true);
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    private Map<String, Object> createNativeOrder(PayOrder order, PaymentCreateOrderRequest request) throws Exception {
        order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
        Map<String, Object> reqBody = buildCommonOrderBody(order, request);
        Map<String, Object> response = postWechatApi(NATIVE_ORDER_PATH, reqBody);
        String codeUrl = asString(response.get("code_url"));
        if (isBlank(codeUrl)) {
            throw new PaymentException("wechat_code_url_missing", "微信 Native 下单未返回 code_url", HttpStatus.BAD_GATEWAY);
        }
        order.setWechatCodeUrl(codeUrl);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("codeUrl", codeUrl);
        payload.put("mode", "NATIVE");
        payload.put("fallbackQrEnabled", paymentProperties.isFallbackQrEnabled());
        appendOfficialApiMeta(payload, NATIVE_ORDER_PATH);
        payload.put("notifyUrl", config.getNotifyUrl());
        payload.put("enabled", true);
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    private Map<String, Object> buildMockPayload(PayOrder order, PaymentCreateOrderRequest request, String scene) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("wechat_jsapi".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
            order.setWechatPrepayId("mock_prepay_" + order.getOutTradeNo());
            payload.put("appId", isBlank(config.getAppId()) ? "mock-wechat-app" : config.getAppId());
            payload.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
            payload.put("nonceStr", "mocknonce");
            payload.put("package", "prepay_id=" + order.getWechatPrepayId());
            payload.put("signType", "RSA");
            payload.put("paySign", "MOCK_PAY_SIGN");
            payload.put("mode", "JSAPI");
            appendOfficialApiMeta(payload, JSAPI_ORDER_PATH);
        } else if ("wechat_h5".equals(scene)) {
            order.setTradeType(PayOrder.TRADE_TYPE_H5);
            String mwebUrl = isBlank(order.getReturnUrl()) ? "http://127.0.0.1:5173/?mock_wechat_h5=1" : order.getReturnUrl();
            order.setWechatMwebUrl(mwebUrl);
            payload.put("mwebUrl", mwebUrl);
            payload.put("mode", "H5");
            appendOfficialApiMeta(payload, H5_ORDER_PATH);
        } else {
            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
            String codeUrl = "mock-wechat://" + order.getOutTradeNo();
            order.setWechatCodeUrl(codeUrl);
            payload.put("codeUrl", codeUrl);
            payload.put("mode", "NATIVE");
            appendOfficialApiMeta(payload, NATIVE_ORDER_PATH);
        }
        payload.put("enabled", false);
        payload.put("mock", true);
        payload.put("mockHint", "当前为本地开发模拟支付，可在后台补单或调用 dev/mark-paid 完成测试。");
        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
        return payload;
    }

    private Map<String, Object> buildCommonOrderBody(PayOrder order, PaymentCreateOrderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.getAppId());
        body.put("mchid", config.getMchId());
        body.put("description", order.getSubject());
        body.put("out_trade_no", order.getOutTradeNo());
        body.put("notify_url", config.getNotifyUrl());
        body.put("amount", Map.of("total", order.getAmountFen(), "currency", "CNY"));

        String attach = buildAttachPayload(order, request);
        if (!isBlank(attach)) {
            body.put("attach", attach);
            order.setAttachPayload(attach);
        }
        return body;
    }

    private String buildAttachPayload(PayOrder order, PaymentCreateOrderRequest request) {
        try {
            Map<String, Object> attach = new LinkedHashMap<>();
            attach.put("reportType", order.getReportType());
            attach.put("scene", order.getSceneCode());
            attach.put("channel", order.getChannel());
            attach.put("phone", request.getPhone());
            attach.put("deviceToken", request.getClientContext() == null ? null : request.getClientContext().getDeviceToken());
            return objectMapper.writeValueAsString(attach);
        } catch (Exception ex) {
            log.warn("Build wechat attach payload failed: outTradeNo={}", order.getOutTradeNo(), ex);
            return null;
        }
    }

    private Map<String, Object> postWechatApi(String path, Map<String, Object> body) throws Exception {
        String requestJson = objectMapper.writeValueAsString(body);
        String nonceStr = randomNonce();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String message = "POST\n" + path + "\n" + timestamp + "\n" + nonceStr + "\n" + requestJson + "\n";
        String signature = sign(message, loadMerchantPrivateKey());
        String authorization = AUTH_SCHEME
                + " mchid=\"" + config.getMchId() + "\","
                + "nonce_str=\"" + nonceStr + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + config.getMchSerialNo() + "\","
                + "signature=\"" + signature + "\"";

        WebClient client = WebClient.builder()
                .baseUrl(API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authorization)
                .build();

        ResponseEntity<String> response = client.post()
                .uri(path)
                .bodyValue(requestJson)
                .exchangeToMono(resp -> resp.toEntity(String.class))
                .onErrorResume(ex -> {
                    log.error("Wechat pay HTTP request failed: path={}", path, ex);
                    return Mono.error(new PaymentException("wechat_http_failed", "微信支付请求失败: " + ex.getMessage(), HttpStatus.BAD_GATEWAY));
                })
                .block();
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            throw new PaymentException("wechat_empty_response", "微信支付返回为空", HttpStatus.BAD_GATEWAY);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("Wechat pay HTTP error: path={}, status={}, body={}", path, response.getStatusCode(), response.getBody());
            throw new PaymentException("wechat_http_failed", "微信支付请求失败: " + response.getBody(), HttpStatus.BAD_GATEWAY);
        }
        return objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
    }

    private List<Map<String, Object>> fetchPlatformCertificateEntries() throws Exception {
        String nonceStr = randomNonce();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String message = "GET\n" + CERTIFICATES_PATH + "\n" + timestamp + "\n" + nonceStr + "\n\n";
        String signature = sign(message, loadMerchantPrivateKey());
        String authorization = AUTH_SCHEME
                + " mchid=\"" + config.getMchId() + "\","
                + "nonce_str=\"" + nonceStr + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + config.getMchSerialNo() + "\","
                + "signature=\"" + signature + "\"";

        WebClient client = WebClient.builder()
                .baseUrl(API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authorization)
                .build();

        ResponseEntity<String> response = client.get()
                .uri(CERTIFICATES_PATH)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(ex -> Mono.error(new PaymentException("wechat_certificates_http_failed", "微信平台证书下载失败", HttpStatus.BAD_GATEWAY)))
                .block();
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            throw new PaymentException("wechat_certificates_empty", "微信平台证书返回为空", HttpStatus.BAD_GATEWAY);
        }
        Map<String, Object> root = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
        Object data = root.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private String decryptAesGcm(String ciphertext, String nonce, String associatedData, String apiV3Key) throws Exception {
        byte[] cipherData = Base64.getDecoder().decode(ciphertext);
        byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);
        if (!isBlank(associatedData)) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(cipherData);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private RSAPrivateKey loadMerchantPrivateKey() {
        if (isBlank(config.getPrivateKeyPath())) {
            throw new PaymentException("wechat_private_key_missing", "缺少 WECHAT_PAY_PRIVATE_KEY_PATH", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            String pem = Files.readString(Path.of(config.getPrivateKeyPath()), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
            return (RSAPrivateKey) privateKey;
        } catch (Exception ex) {
            throw new PaymentException("wechat_private_key_load_failed", "微信商户私钥加载失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private X509Certificate loadPlatformCertificate(String expectedSerial) {
        try {
            Path certPath = resolvePlatformCertificatePath();
            if (Files.exists(certPath)) {
                X509Certificate certificate = readCertificate(certPath);
                if (isBlank(expectedSerial) || certificate.getSerialNumber().toString(16).equalsIgnoreCase(expectedSerial)) {
                    return certificate;
                }
            }
            return downloadAndCachePlatformCertificate(expectedSerial, certPath);
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException("wechat_platform_cert_load_failed", "微信平台证书加载失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private X509Certificate downloadAndCachePlatformCertificate(String expectedSerial, Path certPath) {
        try {
            List<Map<String, Object>> entries = fetchPlatformCertificateEntries();
            if (entries.isEmpty()) {
                throw new PaymentException("wechat_platform_cert_empty", "微信平台证书列表为空", HttpStatus.BAD_GATEWAY);
            }
            List<PlatformCertificateCandidate> candidates = new ArrayList<>();
            for (Map<String, Object> item : entries) {
                String serialNo = asString(item.get("serial_no"));
                String effectiveTime = asString(item.get("effective_time"));
                String expireTime = asString(item.get("expire_time"));
                Object encryptObj = item.get("encrypt_certificate");
                if (!(encryptObj instanceof Map<?, ?> encryptCertificate)) {
                    continue;
                }
                String associatedData = asString(encryptCertificate.get("associated_data"));
                String nonce = asString(encryptCertificate.get("nonce"));
                String ciphertext = asString(encryptCertificate.get("ciphertext"));
                if (isBlank(ciphertext) || isBlank(nonce)) {
                    continue;
                }
                String pem = decryptAesGcm(ciphertext, nonce, associatedData, config.getApiV3Key());
                X509Certificate certificate = parseCertificate(pem);
                candidates.add(new PlatformCertificateCandidate(
                        serialNo,
                        effectiveTime == null ? null : OffsetDateTime.parse(effectiveTime),
                        expireTime == null ? null : OffsetDateTime.parse(expireTime),
                        pem,
                        certificate
                ));
            }
            if (candidates.isEmpty()) {
                throw new PaymentException("wechat_platform_cert_empty", "微信平台证书解密后为空", HttpStatus.BAD_GATEWAY);
            }
            PlatformCertificateCandidate selected = selectCandidate(candidates, expectedSerial);
            Files.createDirectories(certPath.toAbsolutePath().getParent());
            Files.writeString(certPath, selected.pem(), StandardCharsets.UTF_8);
            config.setPlatformCertPath(certPath.toString());
            log.info("Wechat platform certificate cached: path={}, serial={}", certPath, selected.serialNo());
            return selected.certificate();
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Download wechat platform certificate failed", ex);
            throw new PaymentException("wechat_platform_cert_download_failed", "微信平台证书自动下载失败", HttpStatus.BAD_GATEWAY);
        }
    }

    private PlatformCertificateCandidate selectCandidate(List<PlatformCertificateCandidate> candidates, String expectedSerial) {
        if (!isBlank(expectedSerial)) {
            return candidates.stream()
                    .filter(candidate -> expectedSerial.equalsIgnoreCase(candidate.serialNo()))
                    .findFirst()
                    .orElseThrow(() -> new PaymentException("wechat_platform_cert_not_found", "未找到匹配序列号的平台证书", HttpStatus.BAD_GATEWAY));
        }
        OffsetDateTime now = OffsetDateTime.now();
        return candidates.stream()
                .filter(candidate -> candidate.expireTime() == null || candidate.expireTime().isAfter(now))
                .max(Comparator.comparing(candidate -> candidate.effectiveTime() == null ? OffsetDateTime.MIN : candidate.effectiveTime()))
                .orElse(candidates.get(0));
    }

    private X509Certificate readCertificate(Path certPath) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        try (var inputStream = Files.newInputStream(certPath)) {
            return (X509Certificate) certificateFactory.generateCertificate(inputStream);
        }
    }

    private X509Certificate parseCertificate(String pem) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        try (var inputStream = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
            return (X509Certificate) certificateFactory.generateCertificate(inputStream);
        }
    }

    private Path resolvePlatformCertificatePath() {
        if (!isBlank(config.getPlatformCertPath())) {
            return Path.of(config.getPlatformCertPath());
        }
        Path fallback = Paths.get("data", "wechatpay_platform_cert.pem").toAbsolutePath();
        config.setPlatformCertPath(fallback.toString());
        return fallback;
    }

    private String sign(String message, RSAPrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private void appendOfficialApiMeta(Map<String, Object> payload, String createOrderPath) {
        payload.put("officialBaseUrl", API_BASE_URL);
        payload.put("officialBackupBaseUrl", API_BACKUP_BASE_URL);
        payload.put("officialCreateOrderPath", createOrderPath);
        payload.put("officialQueryOrderPathTemplate", QUERY_ORDER_PATH_TEMPLATE);
    }

    private void ensureConfigured() {
        if (isBlank(config.getMchId())
                || isBlank(config.getAppId())
                || isBlank(config.getAppSecret())
                || isBlank(config.getApiV3Key())
                || isBlank(config.getMchSerialNo())
                || isBlank(config.getPrivateKeyPath())
                || isBlank(config.getNotifyUrl())) {
            throw new PaymentException(
                    "wechat_config_missing",
                    "微信支付配置不完整，请检查 MCH_ID / APP_ID / APP_SECRET / API_V3_KEY / MCH_SERIAL_NO / PRIVATE_KEY_PATH / NOTIFY_URL",
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

    private String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String defaultClientIp(String clientIp) {
        return isBlank(clientIp) ? "127.0.0.1" : clientIp;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record PlatformCertificateCandidate(
            String serialNo,
            OffsetDateTime effectiveTime,
            OffsetDateTime expireTime,
            String pem,
            X509Certificate certificate
    ) {
    }
}
