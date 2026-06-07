package com.zodiac.api.service;

import com.zodiac.api.config.PaymentProperties;
import com.zodiac.api.dto.PaymentConsumeResponse;
import com.zodiac.api.dto.PaymentCreateOrderRequest;
import com.zodiac.api.dto.PaymentOrderResponse;
import com.zodiac.api.dto.PaymentOrderStatusResponse;
import com.zodiac.api.dto.WechatJsapiPrepareRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.exception.PaymentException;
import com.zodiac.api.repository.PayOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacadeService {

    private static final String ORDER_PREFIX = "ZD";
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final PayOrderRepository payOrderRepository;
    private final WechatPayService wechatPayService;
    private final AlipayService alipayService;
    private final PaymentNotifyService paymentNotifyService;
    private final PaymentEntitlementService paymentEntitlementService;
    private final PaymentProperties paymentProperties;
    private final AnalyticsService analyticsService;

    @Transactional
    public PaymentOrderResponse createOrder(PaymentCreateOrderRequest request, String clientIp) {
        validateAmount(request.getAmountFen());
        String channel = normalizeChannel(request.getChannel());
        String scene = normalizeScene(channel, request.getScene());

        PayOrder order = resolveReusableOrder(request, channel, scene);
        if (order == null) {
            order = buildNewOrder(request, channel, scene, clientIp);
        } else {
            order.setClientIp(clientIp);
            order.setReturnUrl(blankToNull(request.getReturnUrl()));
            order.setSceneCode(scene);
            order.setSubject(resolveSubject(request));
            order.setReportType(blankToNull(request.getReportType()));
            order.setOpenid(blankToNull(request.getOpenid()));
            order.setStatus(PayOrder.STATUS_PAYING);
        }

        Map<String, Object> payPayload = buildPayload(order, request, channel);
        payOrderRepository.save(order);
        analyticsService.recordFrontendEvent(
                buildPaymentAnalytics(AnalyticsService.EVENT_PAYMENT_ORDER_CREATE, channel),
                clientIp,
                requestUserAgent(request)
        );

        return PaymentOrderResponse.builder()
                .outTradeNo(order.getOutTradeNo())
                .status(order.getStatus())
                .channel(channel.toLowerCase())
                .scene(scene)
                .paid(PayOrder.STATUS_PAID.equals(order.getStatus()))
                .expireAt(order.getExpiresAt())
                .payPayload(payPayload)
                .build();
    }

    @Transactional(readOnly = true)
    public PaymentOrderStatusResponse getOrderStatus(String outTradeNo) {
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "订单不存在", HttpStatus.NOT_FOUND));

        return PaymentOrderStatusResponse.builder()
                .outTradeNo(order.getOutTradeNo())
                .status(order.getStatus())
                .paid(PayOrder.STATUS_PAID.equals(order.getStatus()))
                .accessToken(PayOrder.STATUS_PAID.equals(order.getStatus()) ? order.getAccessToken() : null)
                .channel(order.getChannel() == null ? null : order.getChannel().toLowerCase())
                .scene(order.getSceneCode())
                .tokenConsumed(order.getTokenConsumedAt() != null)
                .build();
    }

    @Transactional
    public PaymentConsumeResponse consumeOrderToken(String outTradeNo) {
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "订单不存在", HttpStatus.NOT_FOUND));
        if (order.getAccessToken() == null || order.getAccessToken().isBlank()) {
            throw new PaymentException("payment_not_ready", "订单尚未支付成功，暂无可用凭证", HttpStatus.CONFLICT);
        }
        boolean success = paymentEntitlementService.consumeToken(order.getAccessToken());
        return PaymentConsumeResponse.builder()
                .outTradeNo(outTradeNo)
                .success(success)
                .status(order.getStatus())
                .message(success ? "深度解析凭证已消费" : "深度解析凭证不可用")
                .build();
    }

    @Transactional
    public PaymentOrderResponse prepareWechatJsapi(WechatJsapiPrepareRequest request, String clientIp) {
        PaymentCreateOrderRequest orderRequest = request.getOrder();
        if (orderRequest == null) {
            throw new PaymentException("wechat_order_required", "缺少订单信息");
        }
        orderRequest.setChannel("wechat");
        orderRequest.setScene("wechat_jsapi");
        orderRequest.setOpenid(wechatPayService.exchangeOpenid(request.getCode()));
        return createOrder(orderRequest, clientIp);
    }

    @Transactional
    public void repairPaid(String outTradeNo, String channel, String transactionId, String rawPayload) {
        String normalizedChannel = normalizeChannel(channel);
        paymentNotifyService.handlePaidNotification(
                normalizedChannel,
                outTradeNo,
                rawPayload == null ? "ADMIN_REPAIR" : rawPayload,
                true,
                transactionId == null ? "MANUAL_REPAIR" : transactionId
        );
        analyticsService.recordFrontendEvent(
                buildPaymentAnalytics(AnalyticsService.EVENT_PAYMENT_REPAIR, normalizedChannel),
                null,
                null
        );
    }

    @Transactional
    public void closeOrder(String outTradeNo) {
        PayOrder order = payOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentException("order_not_found", "订单不存在", HttpStatus.NOT_FOUND));
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            throw new PaymentException("order_paid", "已支付订单不可关闭", HttpStatus.CONFLICT);
        }
        order.setStatus(PayOrder.STATUS_CLOSED);
        order.setClosedAt(LocalDateTime.now());
        payOrderRepository.save(order);
        paymentNotifyService.writeLog(outTradeNo, order.getChannel(), "ORDER_CLOSE", true, "CLOSED", null, "ADMIN_CLOSE");
    }

    public boolean isTokenValid(String token) {
        return paymentEntitlementService.isTokenValid(token);
    }

    public boolean consumeToken(String token) {
        return paymentEntitlementService.consumeToken(token);
    }

    private PayOrder resolveReusableOrder(PaymentCreateOrderRequest request, String channel, String scene) {
        if (request.getOutTradeNo() != null && !request.getOutTradeNo().isBlank()) {
            return payOrderRepository.findByOutTradeNo(request.getOutTradeNo())
                    .filter(order -> !isExpired(order))
                    .orElse(null);
        }
        String deviceToken = request.getClientContext() == null ? null : blankToNull(request.getClientContext().getDeviceToken());
        if (deviceToken == null) {
            return null;
        }
        return payOrderRepository.findFirstByDeviceTokenAndStatusInOrderByCreatedAtDesc(
                        deviceToken,
                        List.of(PayOrder.STATUS_CREATED, PayOrder.STATUS_PAYING))
                .filter(order -> channel.equals(order.getChannel()))
                .filter(order -> scene.equals(order.getSceneCode()))
                .filter(order -> !isExpired(order))
                .orElse(null);
    }

    private PayOrder buildNewOrder(PaymentCreateOrderRequest request, String channel, String scene, String clientIp) {
        PayOrder order = new PayOrder();
        order.setOutTradeNo(generateOutTradeNo());
        order.setChannel(channel);
        order.setTradeType(resolveTradeType(channel, scene));
        order.setSceneCode(scene);
        order.setStatus(PayOrder.STATUS_PAYING);
        order.setSubject(resolveSubject(request));
        order.setAmountFen(paymentProperties.getAmountFen());
        order.setClientIp(clientIp);
        order.setOpenid(blankToNull(request.getOpenid()));
        order.setReportType(blankToNull(request.getReportType()));
        order.setDeviceToken(request.getClientContext() == null ? null : blankToNull(request.getClientContext().getDeviceToken()));
        order.setReturnUrl(blankToNull(request.getReturnUrl()));
        order.setExpiresAt(LocalDateTime.now().plusMinutes(paymentProperties.getOrderExpireMinutes()));
        return order;
    }

    private Map<String, Object> buildPayload(PayOrder order, PaymentCreateOrderRequest request, String channel) {
        Map<String, Object> payload = PayOrder.CHANNEL_WECHAT.equals(channel)
                ? wechatPayService.buildPayPayload(order, request)
                : alipayService.buildPayPayload(order, request);
        payload.put("outTradeNo", order.getOutTradeNo());
        payload.put("amountFen", order.getAmountFen());
        return payload;
    }

    private void validateAmount(Integer amountFen) {
        if (amountFen == null || amountFen != paymentProperties.getAmountFen()) {
            throw new PaymentException(
                    "amount_invalid",
                    "支付金额必须与后端配置一致",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private String normalizeChannel(String channel) {
        if ("wechat".equalsIgnoreCase(channel)) {
            return PayOrder.CHANNEL_WECHAT;
        }
        if ("alipay".equalsIgnoreCase(channel)) {
            return PayOrder.CHANNEL_ALIPAY;
        }
        throw new PaymentException("channel_invalid", "不支持的支付渠道");
    }

    private String normalizeScene(String channel, String scene) {
        if (PayOrder.CHANNEL_WECHAT.equals(channel)) {
            if ("wechat_jsapi".equalsIgnoreCase(scene)) return "wechat_jsapi";
            if ("wechat_h5".equalsIgnoreCase(scene)) return "wechat_h5";
            if ("wechat_native".equalsIgnoreCase(scene)) return "wechat_native";
            return "wechat_h5";
        }
        if ("alipay_wap".equalsIgnoreCase(scene)) {
            return "alipay_wap";
        }
        return "alipay_wap";
    }

    private String resolveTradeType(String channel, String scene) {
        if (PayOrder.CHANNEL_WECHAT.equals(channel)) {
            if ("wechat_jsapi".equals(scene)) return PayOrder.TRADE_TYPE_JSAPI;
            if ("wechat_native".equals(scene)) return PayOrder.TRADE_TYPE_NATIVE;
            return PayOrder.TRADE_TYPE_H5;
        }
        return PayOrder.TRADE_TYPE_WAP;
    }

    private boolean isExpired(PayOrder order) {
        if (order.getExpiresAt() == null) {
            return false;
        }
        if (order.getExpiresAt().isBefore(LocalDateTime.now())) {
            order.setStatus(PayOrder.STATUS_CLOSED);
            order.setClosedAt(LocalDateTime.now());
            payOrderRepository.save(order);
            return true;
        }
        return false;
    }

    private String generateOutTradeNo() {
        StringBuilder sb = new StringBuilder(ORDER_PREFIX);
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String resolveSubject(PaymentCreateOrderRequest request) {
        if (request.getSubject() != null && !request.getSubject().isBlank()) {
            return request.getSubject().trim();
        }
        return "深度解析解锁";
    }

    private String requestUserAgent(PaymentCreateOrderRequest request) {
        return request.getClientContext() == null ? null : request.getClientContext().getUserAgent();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private com.zodiac.api.dto.AnalyticsEventRequest buildPaymentAnalytics(String eventType, String channel) {
        com.zodiac.api.dto.AnalyticsEventRequest request = new com.zodiac.api.dto.AnalyticsEventRequest();
        request.setEventType(eventType);
        request.setChannel(channel == null ? null : channel.toLowerCase());
        return request;
    }
}
