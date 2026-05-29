package com.zodiac.api.service;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEntitlementService {

    private static final SecureRandom RNG = new SecureRandom();

    private final PayOrderRepository payOrderRepository;

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return payOrderRepository.findByAccessToken(token)
                .filter(this::isEntitlementAvailable)
                .isPresent();
    }

    @Transactional
    public boolean consumeToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return payOrderRepository.findByAccessToken(token)
                .filter(this::isEntitlementAvailable)
                .map(order -> {
                    order.setTokenConsumedAt(LocalDateTime.now());
                    payOrderRepository.save(order);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public PayOrder requireConsumableToken(String token) {
        return payOrderRepository.findByAccessToken(token)
                .filter(this::isEntitlementAvailable)
                .orElseThrow(() -> new PaymentException(
                        "payment_token_invalid",
                        "深度解析支付凭证无效、已过期或已使用",
                        HttpStatus.FORBIDDEN
                ));
    }

    @Transactional
    public String ensureAccessToken(PayOrder order) {
        if (order.getAccessToken() == null || order.getAccessToken().isBlank()) {
            order.setAccessToken(generateToken());
            payOrderRepository.save(order);
        }
        return order.getAccessToken();
    }

    @Transactional
    public void markPaid(PayOrder order) {
        if (PayOrder.STATUS_PAID.equals(order.getStatus())) {
            ensureAccessToken(order);
            return;
        }
        order.setStatus(PayOrder.STATUS_PAID);
        order.setPaidAt(LocalDateTime.now());
        ensureAccessToken(order);
        payOrderRepository.save(order);
        log.info("Payment entitlement granted: outTradeNo={}", order.getOutTradeNo());
    }

    private boolean isEntitlementAvailable(PayOrder order) {
        if (!PayOrder.STATUS_PAID.equals(order.getStatus())) {
            return false;
        }
        if (order.getTokenConsumedAt() != null) {
            return false;
        }
        if (order.getPaidAt() != null
                && order.getPaidAt().plusHours(PayOrder.TOKEN_EXPIRE_HOURS).isBefore(LocalDateTime.now())) {
            return false;
        }
        return true;
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
