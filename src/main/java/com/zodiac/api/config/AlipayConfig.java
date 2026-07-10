package com.zodiac.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.alipay")
public class AlipayConfig {

    private boolean enabled;
    private String appId;
    private String privateKeyPath;
    private String privateKey;
    private String publicKey;
    private String notifyUrl;
    private String returnUrl;
    private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
    private String signType = "RSA2";
}
