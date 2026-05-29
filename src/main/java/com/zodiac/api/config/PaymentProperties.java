package com.zodiac.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.common")
public class PaymentProperties {

    private int amountFen = 1990;
    private int orderExpireMinutes = 30;
    private long successPollIntervalMs = 3000L;
    private long successPollTimeoutMs = 180000L;
    private boolean fallbackQrEnabled = false;
}
