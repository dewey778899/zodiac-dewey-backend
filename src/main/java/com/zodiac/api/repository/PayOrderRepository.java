package com.zodiac.api.repository;

import com.zodiac.api.entity.PayOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PayOrderRepository extends JpaRepository<PayOrder, Long> {
    Optional<PayOrder> findByOutTradeNo(String outTradeNo);
    Optional<PayOrder> findByAccessToken(String accessToken);
    Optional<PayOrder> findFirstByDeviceTokenAndStatusInOrderByCreatedAtDesc(String deviceToken, Iterable<String> statuses);

    Page<PayOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<PayOrder> findByChannelAndStatusOrderByCreatedAtDesc(String channel, String status, Pageable pageable);
    Page<PayOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByStatus(String status);
    long countByChannel(String channel);
    long countByChannelAndStatus(String channel, String status);
    long countByCreatedAtGreaterThanEqual(LocalDateTime start);
    long countByStatusAndCreatedAtGreaterThanEqual(String status, LocalDateTime start);
    long countByChannelAndCreatedAtGreaterThanEqual(String channel, LocalDateTime start);
    long countByChannelAndStatusAndCreatedAtGreaterThanEqual(String channel, String status, LocalDateTime start);

    @Query("select count(p) from PayOrder p where p.notifyVerified = false")
    long countNotifyVerifyFailed();

    @Query("select count(p) from PayOrder p where p.notifyVerified = false and p.createdAt >= :start")
    long countNotifyVerifyFailedSince(@Param("start") LocalDateTime start);

    @Query("select count(p) from PayOrder p where p.status = 'PAID' and p.tokenConsumedAt is not null")
    long countConsumedPaidOrders();

    @Query("select count(p) from PayOrder p where p.status = 'PAID' and p.tokenConsumedAt is not null and p.createdAt >= :start")
    long countConsumedPaidOrdersSince(@Param("start") LocalDateTime start);
}
