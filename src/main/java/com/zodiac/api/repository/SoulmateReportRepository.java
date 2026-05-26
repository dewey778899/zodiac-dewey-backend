package com.zodiac.api.repository;

import com.zodiac.api.entity.SoulmateReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SoulmateReportRepository extends JpaRepository<SoulmateReport, Long> {

    Optional<SoulmateReport> findByReportUid(String reportUid);

    @Query("SELECT COUNT(r) FROM SoulmateReport r WHERE r.createdAt >= :startOfDay")
    long countTodayReports(@Param("startOfDay") LocalDateTime startOfDay);

    @Query("SELECT COUNT(r) FROM SoulmateReport r WHERE r.ipAddress = :ip AND r.createdAt >= :startOfDay")
    long countTodayByIp(@Param("ip") String ip, @Param("startOfDay") LocalDateTime startOfDay);

    long countByModelCode(String modelCode);

    long countByModelCodeAndCreatedAtGreaterThanEqual(String modelCode, LocalDateTime start);

    List<SoulmateReport> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(LocalDateTime start);

    @Query("SELECT r.createdAt, r.modelCode FROM SoulmateReport r WHERE r.createdAt >= :start ORDER BY r.createdAt ASC")
    List<Object[]> findCreatedAtAndModelSince(@Param("start") LocalDateTime start);

    @Query("""
            SELECT r FROM SoulmateReport r
            WHERE (:query IS NULL OR :query = ''
                OR LOWER(r.reportUid) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(r.userAName) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(r.userBName) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(r.modelCode) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY r.createdAt DESC
            """)
    Page<SoulmateReport> searchReports(@Param("query") String query, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE SoulmateReport r SET r.wechatId = :wechatId WHERE r.reportUid = :uid")
    int updateWechatId(@Param("uid") String uid, @Param("wechatId") String wechatId);

    @Modifying
    @Transactional
    @Query("UPDATE SoulmateReport r SET r.sharedCount = r.sharedCount + 1 WHERE r.reportUid = :uid")
    int incrementShareCount(@Param("uid") String uid);
}
