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
            log.info("Rate-limit counters synced from database: {}", count);
        } catch (Exception e) {
            log.warn("Failed to sync rate-limit counters, fallback to 0: {}", e.getMessage());
            globalDailyCounter.set(0);
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDaily() {
        globalDailyCounter.set(0);
        currentDay = LocalDate.now();
        log.info("Daily counters reset");
    }

    public String tryAcquire(String ip) {
        return tryAcquire(ip, null);
    }

    public synchronized String tryAcquire(String ip, String modelCode) {
        if (!LocalDate.now().equals(currentDay)) {
            globalDailyCounter.set(0);
            currentDay = LocalDate.now();
        }

        // Keep lightweight counters for analytics/admin views, but do not block users.
        globalDailyCounter.incrementAndGet();
        ipCounter.get(ip, key -> new AtomicLong(0)).incrementAndGet();

        if ("claude".equalsIgnoreCase(modelCode)) {
            claudeIpCounter.get(ip, key -> new AtomicLong(0)).incrementAndGet();
        }

        return null;
    }

    public void rollback(String ip) {
        rollback(ip, null);
    }

    public void rollback(String ip, String modelCode) {
        globalDailyCounter.updateAndGet(v -> Math.max(0, v - 1));
        AtomicLong ipCount = ipCounter.getIfPresent(ip);
        if (ipCount != null) {
            ipCount.updateAndGet(v -> Math.max(0, v - 1));
        }
        if ("claude".equalsIgnoreCase(modelCode)) {
            AtomicLong claudeCount = claudeIpCounter.getIfPresent(ip);
            if (claudeCount != null) {
                claudeCount.updateAndGet(v -> Math.max(0, v - 1));
            }
        }
    }

    public boolean tryAcquireWechat(String ip) {
        AtomicLong count = wechatIpCounter.get(ip, key -> new AtomicLong(0));
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

    public long getGlobalUsed() {
        return globalDailyCounter.get();
    }

    public int getDailyTotal() {
        return dailyTotal;
    }

    public int getPerIpDaily() {
        return perIpDaily;
    }

    public int getClaudePerIpDaily() {
        return claudePerIpDaily;
    }
}
