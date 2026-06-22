package com.zodiac.api.repository;

import com.zodiac.api.entity.PayOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PayOrderRepository extends JpaRepository<PayOrder, Long> {
    Optional<PayOrder> findByOutTradeNo(String outTradeNo);
    Optional<PayOrder> findByPayjsOrderId(String payjsOrderId);
    Optional<PayOrder> findByAccessToken(String accessToken);

    Page<PayOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<PayOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByStatus(String status);
    long countByStatusAndPaidAtGreaterThanEqual(String status, LocalDateTime paidAt);
    long countByStatusAndChannel(String status, String channel);
    long countByStatusAndChannelAndPaidAtGreaterThanEqual(String status, String channel, LocalDateTime paidAt);
    List<PayOrder> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(LocalDateTime start);
    List<PayOrder> findByPaidAtGreaterThanEqualOrderByPaidAtAsc(LocalDateTime start);

    @Query("""
            SELECT p FROM PayOrder p
            WHERE (:status IS NULL OR :status = '' OR p.status = :status)
              AND (:channel IS NULL OR :channel = '' OR LOWER(COALESCE(p.channel, '')) = LOWER(:channel))
              AND (:query IS NULL OR :query = ''
                OR LOWER(p.outTradeNo) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(COALESCE(p.transactionId, '')) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(COALESCE(p.openid, '')) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(COALESCE(p.phone, '')) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY p.createdAt DESC
            """)
    Page<PayOrder> searchOrders(@Param("status") String status,
                                @Param("channel") String channel,
                                @Param("query") String query,
                                Pageable pageable);
}
