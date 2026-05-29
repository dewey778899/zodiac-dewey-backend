package com.zodiac.api.service;

import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.entity.PaymentNotifyLog;
import com.zodiac.api.exception.PaymentException;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.repository.PaymentNotifyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNotifyService {

    private final PayOrderRepository payOrderRepository;
    private final PaymentNotifyLogRepository paymentNotifyLogRepository;
    private final PaymentEntitlementService paymentEntitlementService;
    private final AnalyticsService analyticsService;

    @Transactional
    public void handlePaidNotification(String channel,
                                       String outTradeNo,
                                       String rawPayload,
                                       boolean verified,
                                       String transactionId) {
        PaymentNotifyLog logEntity = new PaymentNotifyLog();
        logEntity.setOutTradeNo(outTradeNo);
        logEntity.setChannel(channel);
        logEntity.setNotifyType("PAYMENT");
        logEntity.setVerified(verified);
        logEntity.setRawPayload(rawPayload);

        if (!verified) {
            logEntity.setProcessResult("IGNORED");
            logEntity.setErrorMessage("验签失败");
            paymentNotifyLogRepository.save(logEntity);
            return;
        }

        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException(
                        "order_not_found",
                        "订单不存在: " + outTradeNo,
                        HttpStatus.NOT_FOUND
                ));

        order.setNotifyChannel(channel);
        order.setNotifyVerified(true);
        order.setNotifyRaw(rawPayload);
        if (PayOrder.CHANNEL_WECHAT.equals(channel)) {
            order.setWechatTransactionId(transactionId);
        } else if (PayOrder.CHANNEL_ALIPAY.equals(channel)) {
            order.setAlipayTradeNo(transactionId);
        }
        paymentEntitlementService.markPaid(order);
        analyticsService.recordFrontendEvent(buildPaidAnalytics(channel), null, null);

        logEntity.setProcessResult("PAID");
        paymentNotifyLogRepository.save(logEntity);
        log.info("Payment notify processed: channel={}, outTradeNo={}", channel, outTradeNo);
    }

    @Transactional
    public void writeLog(String outTradeNo,
                         String channel,
                         String notifyType,
                         boolean verified,
                         String processResult,
                         String errorMessage,
                         String rawPayload) {
        PaymentNotifyLog logEntity = new PaymentNotifyLog();
        logEntity.setOutTradeNo(outTradeNo);
        logEntity.setChannel(channel);
        logEntity.setNotifyType(notifyType);
        logEntity.setVerified(verified);
        logEntity.setProcessResult(processResult);
        logEntity.setErrorMessage(errorMessage);
        logEntity.setRawPayload(rawPayload);
        paymentNotifyLogRepository.save(logEntity);
    }

    private com.zodiac.api.dto.AnalyticsEventRequest buildPaidAnalytics(String channel) {
        com.zodiac.api.dto.AnalyticsEventRequest request = new com.zodiac.api.dto.AnalyticsEventRequest();
        request.setEventType(AnalyticsService.EVENT_PAYMENT_ORDER_PAID);
        request.setChannel(channel == null ? null : channel.toLowerCase());
        return request;
    }
}
