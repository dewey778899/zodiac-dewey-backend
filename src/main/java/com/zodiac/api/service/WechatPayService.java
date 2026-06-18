     1|     1|package com.zodiac.api.service;
     2|     2|
     3|     3|import com.fasterxml.jackson.databind.JsonNode;
     4|     4|import com.fasterxml.jackson.databind.ObjectMapper;
     5|     5|import com.zodiac.api.config.PaymentProperties;
     6|     6|import com.zodiac.api.config.WechatPayConfig;
     7|     7|import com.zodiac.api.dto.PaymentCreateOrderRequest;
     8|     8|import com.zodiac.api.entity.PayOrder;
     9|     9|import com.zodiac.api.exception.PaymentException;
    10|    10|import lombok.RequiredArgsConstructor;
    11|    11|import lombok.extern.slf4j.Slf4j;
    12|    12|import org.springframework.http.HttpStatus;
    13|    13|import org.springframework.stereotype.Service;
    14|    14|
    15|    15|import java.net.URI;
    16|    16|import java.net.URLEncoder;
    17|    17|import java.net.http.HttpClient;
    18|    18|import java.net.http.HttpRequest;
    19|    19|import java.net.http.HttpResponse;
    20|    20|import java.nio.charset.StandardCharsets;
    21|    21|import java.nio.file.Files;
    22|    22|import java.nio.file.Path;
    23|    23|import java.security.KeyFactory;
    24|    24|import java.security.Signature;
    25|    25|import java.security.spec.PKCS8EncodedKeySpec;
    26|    26|import java.time.Instant;
    27|    27|import java.util.Base64;
    28|    28|import java.util.LinkedHashMap;
    29|    29|import java.util.Map;
    30|    30|import java.util.UUID;
    31|    31|
    32|    32|@Slf4j
    33|    33|@Service
    34|    34|@RequiredArgsConstructor
    35|    35|public class WechatPayService {
    36|    36|
    37|    37|    private static final String WECHAT_API_HOST = "https://api.mch.weixin.qq.com";
    38|    38|    private static final String JSAPI_PATH = "/v3/pay/transactions/jsapi";
    39|    39|    private static final String H5_PATH = "/v3/pay/transactions/h5";
    40|    40|    private static final String NATIVE_PATH = "/v3/pay/transactions/native";
    41|    41|    private static final String OAUTH_PATH="https:...oken";
    42|    42|    private static final String OAUTH_OPENID_PATH="https:...sion";
    43|    43|
    44|    44|    private final WechatPayConfig config;
    45|    45|    private final PaymentProperties paymentProperties;
    46|    46|    private final ObjectMapper objectMapper = new ObjectMapper();
    47|    47|    private final HttpClient httpClient = HttpClient.newHttpClient();
    48|    48|
    49|    49|    public Map<String, Object> buildPayPayload(PayOrder order, PaymentCreateOrderRequest request) {
    50|    50|        if (!config.isEnabled()) {
    51|    51|            if (!paymentProperties.isDevMockEnabled()) {
    52|    52|                throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
    53|    53|            }
    54|    54|            return buildMockPayload(order, request);
    55|    55|        }
    56|    56|        ensureConfigured();
    57|    57|
    58|    58|        String scene = normalizeScene(request.getScene());
    59|    59|        return callWechatPayApi(order, request, scene);
    60|    60|    }
    61|    61|
    62|    62|    public String exchangeOpenid(String code) {
    63|    63|        if (!config.isEnabled()) {
    64|    64|            throw new PaymentException("wechat_disabled", "微信支付未开启", HttpStatus.SERVICE_UNAVAILABLE);
    65|    65|        }
    66|    66|        ensureConfigured();
    67|    67|        if (code == null || code.isBlank()) {
    68|    68|            throw new PaymentException("wechat_code_required", "微信授权 code 不能为空");
    69|    69|        }
    70|    70|        return exchangeOpenidViaApi(code);
    71|    71|    }
    72|    72|
    73|    73|    // ==================== Real API ====================
    74|    74|
    75|    75|    private Map<String, Object> callWechatPayApi(PayOrder order, PaymentCreateOrderRequest request, String scene) {
    76|    76|        try {
    77|    77|            String path;
    78|    78|            Map<String, Object> body = new LinkedHashMap<>();
    79|    79|            body.put("appid", config.getAppId());
    80|    80|            body.put("mchid", config.getMchId());
    81|    81|            body.put("description", resolveSubject(request));
    82|    82|            body.put("out_trade_no", order.getOutTradeNo());
    83|    83|            body.put("notify_url", config.getNotifyUrl());
    84|    84|
    85|    85|            Map<String, Object> amount = new LinkedHashMap<>();
    86|    86|            amount.put("total", order.getAmountFen());
    87|    87|            amount.put("currency", "CNY");
    88|    88|            body.put("amount", amount);
    89|    89|
    90|    90|            if ("wechat_jsapi".equals(scene)) {
    91|    91|                path = JSAPI_PATH;
    92|    92|                if (order.getOpenid() == null || order.getOpenid().isBlank()) {
    93|    93|                    throw new PaymentException("wechat_openid_required", "微信内支付缺少 openid", HttpStatus.BAD_REQUEST);
    94|    94|                }
    95|    95|                Map<String, String> payer = new LinkedHashMap<>();
    96|    96|                payer.put("openid", order.getOpenid());
    97|    97|                body.put("payer", payer);
    98|    98|            } else if ("wechat_h5".equals(scene)) {
    99|    99|                path = H5_PATH;
   100|   100|                Map<String, Object> sceneInfo = new LinkedHashMap<>();
   101|   101|                Map<String, String> h5Info = new LinkedHashMap<>();
   102|   102|                h5Info.put("type", "Wap");
   103|   103|                h5Info.put("wap_url", blankToNull(order.getReturnUrl()) != null ? order.getReturnUrl() : config.getH5ReturnUrl());
   104|   104|                h5Info.put("wap_name", "小登哥星盘");
   105|   105|                sceneInfo.put("h5_info", h5Info);
   106|   106|                sceneInfo.put("payer_client_ip", blankToNull(order.getClientIp()) != null ? order.getClientIp() : "127.0.0.1");
   107|   107|                body.put("scene_info", sceneInfo);
   108|   108|            } else {
   109|   109|                path = NATIVE_PATH;
   110|   110|            }
   111|   111|
   112|   112|            String bodyJson = objectMapper.writeValueAsString(body);
   113|   113|            String fullUrl = WECHAT_API_HOST + path;
   114|   114|
   115|   115|            HttpRequest httpRequest = HttpRequest.newBuilder()
   116|   116|                    .uri(URI.create(fullUrl))
   117|   117|                    .header("Content-Type", "application/json")
   118|   118|                    .header("Accept", "application/json")
   119|   119|                    .header("Authorization", buildWechatAuthHeader("POST", path, bodyJson))
   120|   120|                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
   121|   121|                    .build();
   122|   122|
   123|   123|            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
   124|   124|            JsonNode respJson = objectMapper.readTree(response.body());
   125|   125|
   126|   126|            if (response.statusCode() >= 200 && response.statusCode() < 300) {
   127|   127|                return buildSuccessPayload(order, scene, respJson);
   128|   128|            } else {
   129|   129|                String errMsg = respJson.has("message") ? respJson.get("message").asText() : response.body();
   130|   130|                log.error("Wechat pay API error: status={}, body={}", response.statusCode(), response.body());
   131|   131|                throw new PaymentException("wechat_api_error", "微信支付下单失败: " + errMsg, HttpStatus.BAD_GATEWAY);
   132|   132|            }
   133|   133|        } catch (PaymentException e) {
   134|   134|            throw e;
   135|   135|        } catch (Exception e) {
   136|   136|            log.error("Wechat pay API call failed", e);
   137|   137|            throw new PaymentException("wechat_pay_error", "微信支付系统异常，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
   138|   138|        }
   139|   139|    }
   140|   140|
   141|   141|    private Map<String, Object> buildSuccessPayload(PayOrder order, String scene, JsonNode respJson) {
   142|   142|        try {
   143|   143|        Map<String, Object> payload = new LinkedHashMap<>();
   144|   144|        String prepayId = respJson.has("prepay_id") ? respJson.get("prepay_id").asText() : "";
   145|   145|
   146|   146|        if ("wechat_jsapi".equals(scene)) {
   147|   147|            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
   148|   148|            order.setWechatPrepayId(prepayId);
   149|   149|
   150|   150|            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
   151|   151|            long timestamp = Instant.now().getEpochSecond();
   152|   152|            String packageStr = "prepay_id=" + prepayId;
   153|   153|
   154|   154|            // Generate paySign for JSAPI
   155|   155|            String signStr = config.getAppId() + "\n" + timestamp + "\n" + nonceStr + "\n" + packageStr + "\n";
   156|   156|            String paySign = signWithPrivateKey(signStr);
   157|   157|
   158|   158|            payload.put("appId", config.getAppId());
   159|   159|            payload.put("timeStamp", String.valueOf(timestamp));
   160|   160|            payload.put("nonceStr", nonceStr);
   161|   161|            payload.put("package", packageStr);
   162|   162|            payload.put("signType", "RSA");
   163|   163|            payload.put("paySign", paySign);
   164|   164|            payload.put("mode", "JSAPI");
   165|   165|        } else if ("wechat_h5".equals(scene)) {
   166|   166|            order.setTradeType(PayOrder.TRADE_TYPE_H5);
   167|   167|            String h5Url = respJson.has("h5_url") ? respJson.get("h5_url").asText() : "";
   168|   168|            order.setWechatMwebUrl(h5Url);
   169|   169|            payload.put("mwebUrl", h5Url);
   170|   170|            payload.put("mode", "H5");
   171|   171|        } else {
   172|   172|            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
   173|   173|            String codeUrl = respJson.has("code_url") ? respJson.get("code_url").asText() : "";
   174|   174|            order.setWechatCodeUrl(codeUrl);
   175|   175|            payload.put("codeUrl", codeUrl);
   176|   176|            payload.put("mode", "NATIVE");
   177|   177|        }
   178|   178|
   179|   179|        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
   180|   180|        payload.put("enabled", true);
   181|   181|        return payload;
   182|   182|        } catch (Exception e) {
   183|   183|            log.error("Build success payload failed", e);
   184|   184|            throw new PaymentException("wechat_payload_error", "构造支付结果失败");
   185|   185|        }
   186|   186|    }
   187|   187|
   188|   188|    private String exchangeOpenidViaApi(String code) {
   189|   189|        try {
   190|   190|            String appId = config.getAppId();
   191|   191|            String appSecret = config.getAppSecret();
   192|   192|            if (isBlank(appSecret)) {
   193|   193|                throw new PaymentException("wechat_app_secret_missing", "微信 AppSecret 未配置，无法获取 openid", HttpStatus.SERVICE_UNAVAILABLE);
   194|   194|            }
   195|   195|
   196|   196|            String url = OAUTH_OPENID_PATH + "?appid=" + urlEncode(appId)
   197|   197|                    + "&secret=" + urlEncode(appSecret)
   198|   198|                    + "&js_code=" + urlEncode(code)
   199|   199|                    + "&grant_type=authorization_code";
   200|   200|
   201|   201|            HttpRequest request = HttpRequest.newBuilder()
   202|   202|                    .uri(URI.create(url))
   203|   203|                    .GET()
   204|   204|                    .build();
   205|   205|            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
   206|   206|            JsonNode json = objectMapper.readTree(response.body());
   207|   207|
   208|   208|            if (json.has("openid")) {
   209|   209|                return json.get("openid").asText();
   210|   210|            }
   211|   211|            String errMsg = json.has("errmsg") ? json.get("errmsg").asText("未知错误") : "未知错误";
   212|   212|            throw new PaymentException("wechat_openid_failed", "获取微信 openid 失败: " + errMsg);
   213|   213|        } catch (PaymentException e) {
   214|   214|            throw e;
   215|   215|        } catch (Exception e) {
   216|   216|            log.error("Exchange openid failed", e);
   217|   217|            throw new PaymentException("wechat_openid_error", "微信 openid 获取失败");
   218|   218|        }
   219|   219|    }
   220|   220|
   221|   221|    // ==================== Signing ====================
   222|   222|
   223|   223|    private String buildWechatAuthHeader(String method, String path, String body) {
   224|   224|        try {
   225|   225|            long timestamp = Instant.now().getEpochSecond();
   226|   226|            String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
   227|   227|            String message = method + "\n" + path + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
   228|   228|            String signature = signWithPrivateKey(message);
   229|   229|
   230|   230|            return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + config.getMchId()
   231|   231|                    + "\",nonce_str=\"" + nonceStr
   232|   232|                    + "\",signature=\"" + signature
   233|   233|                    + "\",timestamp=\"" + timestamp
   234|   234|                    + "\",serial_no=\"" + (blankToNull(config.getMchSerialNo()) != null ? config.getMchSerialNo() : "") + "\"";
   235|   235|        } catch (Exception e) {
   236|   236|            throw new PaymentException("wechat_sign_error", "微信支付签名失败", HttpStatus.INTERNAL_SERVER_ERROR);
   237|   237|        }
   238|   238|    }
   239|   239|
   240|   240|    private String signWithPrivateKey(String message) throws Exception {
   241|   241|        String privateKeyPath = config.getPrivateKeyPath();
   242|   242|        if (isBlank(privateKeyPath)) {
   243|   243|            throw new PaymentException("wechat_private_key_missing", "微信支付私钥路径未配置");
   244|   244|        }
   245|   245|        byte[] keyBytes = Files.readAllBytes(Path.of(privateKeyPath));
   246|   246|        String keyContent = new String(keyBytes).replace("[REDACTED PRIVATE KEY]", "")
   247|   248|                .replaceAll("\\s", "");
   248|   249|        byte[] decoded = Base64.getDecoder().decode(keyContent);
   249|   250|        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
   250|   251|        KeyFactory kf = KeyFactory.getInstance("RSA");
   251|   252|        Signature sign = Signature.getInstance("SHA256withRSA");
   252|   253|        sign.initSign(kf.generatePrivate(spec));
   253|   254|        sign.update(message.getBytes(StandardCharsets.UTF_8));
   254|   255|        return Base64.getEncoder().encodeToString(sign.sign());
   255|   256|    }
   256|   257|
   257|   258|    // ==================== Mock ====================
   258|   259|
   259|   260|    private Map<String, Object> buildMockPayload(PayOrder order, PaymentCreateOrderRequest request) {
   260|   261|        Map<String, Object> payload = new LinkedHashMap<>();
   261|   262|        String scene = normalizeScene(request.getScene());
   262|   263|        if ("wechat_jsapi".equals(scene)) {
   263|   264|            order.setTradeType(PayOrder.TRADE_TYPE_JSAPI);
   264|   265|            order.setWechatPrepayId("mock_prepay_" + order.getOutTradeNo());
   265|   266|            payload.put("appId", "mock-wechat-app");
   266|   267|            payload.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
   267|   268|            payload.put("nonceStr", "mocknonce");
   268|   269|            payload.put("package", "prepay_id=" + order.getWechatPrepayId());
   269|   270|            payload.put("signType", "RSA");
   270|   271|            payload.put("paySign", "MOCK_PAY_SIGN");
   271|   272|            payload.put("mode", "JSAPI");
   272|   273|        } else if ("wechat_h5".equals(scene)) {
   273|   274|            order.setTradeType(PayOrder.TRADE_TYPE_H5);
   274|   275|            String mwebUrl = (order.getReturnUrl() == null || order.getReturnUrl().isBlank())
   275|   276|                    ? "http://127.0.0.1:5173/?mock_wechat_h5=1"
   276|   277|                    : order.getReturnUrl();
   277|   278|            order.setWechatMwebUrl(mwebUrl);
   278|   279|            payload.put("mwebUrl", mwebUrl);
   279|   280|            payload.put("mode", "H5");
   280|   281|        } else {
   281|   282|            order.setTradeType(PayOrder.TRADE_TYPE_NATIVE);
   282|   283|            String codeUrl = "mock-wechat://" + order.getOutTradeNo();
   283|   284|            order.setWechatCodeUrl(codeUrl);
   284|   285|            payload.put("codeUrl", codeUrl);
   285|   286|            payload.put("mode", "NATIVE");
   286|   287|        }
   287|   288|        payload.put("enabled", false);
   288|   289|        payload.put("mock", true);
   289|   290|        payload.put("mockHint", "当前为本地开发模拟支付，可在后台补单或调用 dev/mark-paid 完成测试。");
   290|   291|        payload.put("expireMinutes", paymentProperties.getOrderExpireMinutes());
   291|   292|        return payload;
   292|   293|    }
   293|   294|
   294|   295|    // ==================== Helpers ====================
   295|   296|
   296|   297|    private void ensureConfigured() {
   297|   298|        if (isBlank(config.getMchId())
   298|   299|                || isBlank(config.getAppId())
   299|   300|                || isBlank(config.getApiV3Key())
   300|   301|                || isBlank(config.getNotifyUrl())) {
   301|   302|            throw new PaymentException(
   302|   303|                    "wechat_config_missing",
   303|   304|                    "微信支付配置不完整，请检查 WECHAT_PAY_MCH_ID / WECHAT_PAY_APP_ID / WECHAT_PAY_API_V3_KEY / WECHAT_PAY_NOTIFY_URL",
   304|   305|                    HttpStatus.SERVICE_UNAVAILABLE
   305|   306|            );
   306|   307|        }
   307|   308|    }
   308|   309|
   309|   310|    private String resolveSubject(PaymentCreateOrderRequest request) {
   310|   311|        if (request.getSubject() != null && !request.getSubject().isBlank()) {
   311|   312|            return request.getSubject().trim();
   312|   313|        }
   313|   314|        return "深度解析服务";
   314|   315|    }
   315|   316|
   316|   317|    private String normalizeScene(String scene) {
   317|   318|        if ("wechat_jsapi".equalsIgnoreCase(scene)) {
   318|   319|            return "wechat_jsapi";
   319|   320|        }
   320|   321|        if ("wechat_h5".equalsIgnoreCase(scene)) {
   321|   322|            return "wechat_h5";
   322|   323|        }
   323|   324|        return "wechat_native";
   324|   325|    }
   325|   326|
   326|   327|    private boolean isBlank(String value) {
   327|   328|        return value == null || value.isBlank();
   328|   329|    }
   329|   330|
   330|   331|    private String blankToNull(String value) {
   331|   332|        return value == null || value.isBlank() ? null : value.trim();
   332|   333|    }
   333|   334|
   334|   335|    private String urlEncode(String value) {
   335|   336|        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
   336|   337|    }
   337|   338|}
   338|   339|