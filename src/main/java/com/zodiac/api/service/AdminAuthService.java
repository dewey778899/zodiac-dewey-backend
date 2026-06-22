package com.zodiac.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zodiac.api.exception.AdminAuthException;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class AdminAuthService {

    private final SecureRandom random = new SecureRandom();

    @Value("${admin.username}")
    private String username;

    @Value("${admin.password}")
    private String password;

    @Value("${admin.session-hours:12}")
    private int sessionHours;

    private Cache<String, AdminSession> sessions;

    @PostConstruct
    public void init() {
        this.sessions = Caffeine.newBuilder()
                .expireAfterWrite(sessionHours, TimeUnit.HOURS)
                .maximumSize(100)
                .build();
    }

    public LoginResult login(String usernameInput, String passwordInput) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new AdminAuthException("后台管理员账号未配置，请先设置 ADMIN_USERNAME 和 ADMIN_PASSWORD");
        }
        if (!username.equals(usernameInput) || !password.equals(passwordInput)) {
            throw new AdminAuthException("管理员账号或密码不正确");
        }
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(sessionHours);
        sessions.put(token, new AdminSession(username, expiresAt));
        return new LoginResult(token, expiresAt);
    }

    public void requireValidToken(String token) {
        if (token == null || token.isBlank()) {
            throw new AdminAuthException("请先登录后台");
        }
        AdminSession session = sessions.getIfPresent(token.trim());
        if (session == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AdminAuthException("后台登录已失效，请重新登录");
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String token, LocalDateTime expiresAt) {
    }

    @Getter
    private static class AdminSession {
        private final String username;
        private final LocalDateTime expiresAt;

        private AdminSession(String username, LocalDateTime expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }
}
