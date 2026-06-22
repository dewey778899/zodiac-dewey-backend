package com.zodiac.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentEntitlementService {

    private final PayService payService;

    public boolean consumeToken(String accessToken) {
        return payService.consumeToken(accessToken);
    }

    public boolean isTokenValid(String accessToken) {
        return payService.isTokenValid(accessToken);
    }
}
