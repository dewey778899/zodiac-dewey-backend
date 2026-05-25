package com.zodiac.api.config;

import com.zodiac.api.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppStartupListener {

    private final RateLimitService rateLimitService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("===== 小登哥灵魂合盘 API 启动完成 =====");
        rateLimitService.syncFromDatabase();
    }
}
