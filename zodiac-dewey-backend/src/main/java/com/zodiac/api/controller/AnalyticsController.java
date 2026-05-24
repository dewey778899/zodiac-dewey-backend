package com.zodiac.api.controller;

import com.zodiac.api.dto.AnalyticsEventRequest;
import com.zodiac.api.service.AnalyticsService;
import com.zodiac.api.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PostMapping("/event")
    public ResponseEntity<?> recordEvent(@Valid @RequestBody AnalyticsEventRequest request,
                                         HttpServletRequest httpServletRequest) {
        String ip = IpUtil.getClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");
        analyticsService.recordFrontendEvent(request, ip, userAgent);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
