package com.zodiac.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.wechat")
public class WechatPayConfig {

    private boolean enabled;
    private String mchId;
    private String appId;
    private String apiV3Key;
    private String mchSerialNo;
    private String privateKeyPath;
    private String platformCertPath;
    private String notifyUrl;
    private String h5ReturnUrl;
    private String oauthRedirectUrl;
}
