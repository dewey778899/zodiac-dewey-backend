package com.zodiac.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payjs")
public class PayJsConfig {
    /** PayJS 商户号 */
    private String mchid;
    /** PayJS 通信密钥 */
    private String key;
    /** 异步回调地址（必须公网可访问） */
    private String notifyUrl;
}
