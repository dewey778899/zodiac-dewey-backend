package com.zodiac.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Deprecated
public class PayService {

    private final PaymentFacadeService paymentFacadeService;

    public boolean consumeToken(String token) {
        return paymentFacadeService.consumeToken(token);
    }

    public boolean isTokenValid(String token) {
        return paymentFacadeService.isTokenValid(token);
    }
}
