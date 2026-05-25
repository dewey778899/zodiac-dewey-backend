package com.zodiac.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zodiac.api.repository.SoulmateReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流服务
 * - 全局每日总额度(默认 200 次)
 * - 单 IP 每日次数(默认 3 次)
 * - Claude 专属配额(默认 1 次/天/IP)
 * 
 * 实现方式:
 * 1. 单机模式:使用 Caffeine 本地缓存 + AtomicLong 计数器
 * 2. 分布式模式(预留):可通过配置切换到数据库计数器
 * 
 * TODO: 如需水平扩展，建议:
 *   - 接入 Redis 分布式限流（Redisson RateLimiter）
 *   - 或使用数据库行锁实现分布式计数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final SoulmateReportRepository repository;

    @Value("${ratelimit.daily-total}")
    private int dailyTotal;

    @Value("${ratelimit.per-ip-daily}")
    private int perIpDaily;

    @Value("${ratelimit.claude-per-ip-daily:1}")
    private int claudePerIpDaily;

    private final AtomicLong globalDailyCounter = new AtomicLong(0);
    private volatile LocalDate currentDay = LocalDate.now();

    private final Cache<String, AtomicLong> ipCounter = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

    private final Cache<String, AtomicLong> wechatIpCounter = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

    private final Cache<String, AtomicLong> claudeIpCounter = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

    private static final int WECHAT_PER_IP_DAILY = 5;

    public synchronized void syncFromDatabase() {
        try {
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
            long count = repository.countTodayReports(startOfDay);
            globalDailyCounter.set(count);
            log.info("启动同步:今天已生成 {} 份报告", count);
        } catch (Exception e) {
            log.warn("同步限流计数失败,使用 0: {}", e.getMessage());
            globalDailyCounter.set(0);
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDaily() {
        globalDailyCounter.set(0);
        currentDay = LocalDate.now();
        log.info("日计数器已重置");
    }

    public String tryAcquire(String ip) {
        return tryAcquire(ip, null);
    }

    /**
     * 尝试获取限流配额
     * 使用 synchronized 保证单机并发安全
     * 注意:多实例部署时此限流会失效，需要接入 Redis 分布式限流
     */
    public synchronized String tryAcquire(String ip, String modelCode) {
        if (!LocalDate.now().equals(currentDay)) {
            globalDailyCounter.set(0);
            currentDay = LocalDate.now();
        }

        long now = globalDailyCounter.incrementAndGet();
        if (now > dailyTotal) {
            globalDailyCounter.decrementAndGet();
            return String.format("今日测算名额已满(%d 份),明天再来吧 ✨", dailyTotal);
        }

        AtomicLong ipCount = ipCounter.get(ip, k -> new AtomicLong(0));
        long ipNow = ipCount.incrementAndGet();
        if (ipNow > perIpDaily) {
            ipCount.decrementAndGet();
            globalDailyCounter.decrementAndGet();
            return String.format("你今天已经测了 %d 次了,明天再来吧 💕", perIpDaily);
        }

        if ("claude".equalsIgnoreCase(modelCode)) {
            AtomicLong claudeCount = claudeIpCounter.get(ip, k -> new AtomicLong(0));
            if (claudeCount.incrementAndGet() > claudePerIpDaily) {
                claudeCount.decrementAndGet();
                ipCount.decrementAndGet();
                globalDailyCounter.decrementAndGet();
                return String.format("深度解析今日名额已用完，明天再来吧 💕");
            }
        }

        return null;
    }

    public void rollback(String ip) {
        rollback(ip, null);
    }

    public void rollback(String ip, String modelCode) {
        globalDailyCounter.updateAndGet(v -> Math.max(0, v - 1));
        AtomicLong ipCount = ipCounter.getIfPresent(ip);
        if (ipCount != null) ipCount.updateAndGet(v -> Math.max(0, v - 1));
        if ("claude".equalsIgnoreCase(modelCode)) {
            AtomicLong claudeCount = claudeIpCounter.getIfPresent(ip);
            if (claudeCount != null) claudeCount.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    public boolean tryAcquireWechat(String ip) {
        AtomicLong count = wechatIpCounter.get(ip, k -> new AtomicLong(0));
        // 使用原子 CAS 循环，避免 get+incrementAndGet 的竞态条件
        while (true) {
            long current = count.get();
            if (current >= WECHAT_PER_IP_DAILY) {
                return false;
            }
            if (count.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public long getGlobalUsed() { return globalDailyCounter.get(); }
    public int getDailyTotal() { return dailyTotal; }
}
