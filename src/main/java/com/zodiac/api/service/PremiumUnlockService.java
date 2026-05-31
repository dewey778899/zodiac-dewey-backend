package com.zodiac.api.service;

import com.zodiac.api.dto.PremiumUnlockRequestDto;
import com.zodiac.api.entity.PremiumUnlockRequest;
import com.zodiac.api.repository.PremiumUnlockRequestRepository;
import com.zodiac.api.util.IpUtil;
import com.zodiac.api.dto.CompatibilityRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumUnlockService {

    private static final SecureRandom RNG = new SecureRandom();

    private final PremiumUnlockRequestRepository premiumUnlockRequestRepository;

    @Transactional
    public Map<String, Object> createDouyinUnlock(PremiumUnlockRequestDto request, HttpServletRequest httpRequest) {
        String reportType = normalizeReportType(request.getReportType());
        String accessToken = generateAccessToken();

        PremiumUnlockRequest entity = new PremiumUnlockRequest();
        entity.setSource(PremiumUnlockRequest.SOURCE_DOUYIN_FOLLOW);
        entity.setDouyinName(request.getDouyinName().trim());
        entity.setConfirmedFollowed(Boolean.TRUE);
        entity.setReportType(reportType);
        entity.setAccessToken(accessToken);
        entity.setDeviceToken(trimToNull(request.getDeviceToken()));
        entity.setClientContext(trimToNull(request.getClientContext()));
        entity.setUserAgent(trimToNull(request.getUserAgent()));
        entity.setClientIp(IpUtil.getClientIp(httpRequest));
        premiumUnlockRequestRepository.save(entity);

        log.info("Premium unlock granted by Douyin follow: douyinName={}, reportType={}", entity.getDouyinName(), reportType);

        return Map.of(
                "status", "ok",
                "accessToken", accessToken,
                "message", "已解锁深度解析"
        );
    }

    public boolean isTokenValid(String token) {
        return premiumUnlockRequestRepository.findByAccessToken(token)
                .filter(this::isConsumable)
                .isPresent();
    }

    @Transactional
    public boolean consumeToken(String token) {
        return premiumUnlockRequestRepository.findByAccessToken(token)
                .filter(this::isConsumable)
                .map(record -> {
                    record.setTokenConsumedAt(LocalDateTime.now());
                    premiumUnlockRequestRepository.save(record);
                    return true;
                })
                .orElse(false);
    }

    public Page<PremiumUnlockRequest> listUnlocks(int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return premiumUnlockRequestRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Map<String, Object> toAdminMap(PremiumUnlockRequest record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", record.getId());
        data.put("source", record.getSource());
        data.put("douyinName", record.getDouyinName());
        data.put("reportType", record.getReportType());
        data.put("confirmedFollowed", record.getConfirmedFollowed());
        data.put("tokenConsumed", record.getTokenConsumedAt() != null);
        data.put("tokenConsumedAt", record.getTokenConsumedAt());
        data.put("createdAt", record.getCreatedAt());
        data.put("deviceToken", record.getDeviceToken());
        return data;
    }

    private boolean isConsumable(PremiumUnlockRequest record) {
        return record.getTokenConsumedAt() == null;
    }

    private String normalizeReportType(String reportType) {
        if (CompatibilityRequest.REPORT_TYPE_CAREER.equalsIgnoreCase(reportType)) {
            return CompatibilityRequest.REPORT_TYPE_CAREER;
        }
        if (CompatibilityRequest.REPORT_TYPE_WEALTH.equalsIgnoreCase(reportType)) {
            return CompatibilityRequest.REPORT_TYPE_WEALTH;
        }
        return CompatibilityRequest.REPORT_TYPE_LOVE;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateAccessToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
