package com.zodiac.api.repository;

import com.zodiac.api.entity.PayOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayOrderRepository extends JpaRepository<PayOrder, Long> {
    Optional<PayOrder> findByOutTradeNo(String outTradeNo);
    Optional<PayOrder> findByPayjsOrderId(String payjsOrderId);
    Optional<PayOrder> findByAccessToken(String accessToken);

    Page<PayOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<PayOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByStatus(String status);
}
