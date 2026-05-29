package com.zodiac.api.repository;

import com.zodiac.api.entity.PaymentNotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentNotifyLogRepository extends JpaRepository<PaymentNotifyLog, Long> {
    List<PaymentNotifyLog> findTop20ByOutTradeNoOrderByCreatedAtDesc(String outTradeNo);
}
