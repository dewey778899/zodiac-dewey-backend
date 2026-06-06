package com.zodiac.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiChatService {

    private java.net.http.HttpClient javaHttpClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ai.api.key:}")
    private String apiKey;

    @Value("${ai.api.url:https://api.deepseek.com/chat/completions}")
    private String apiUrl;

    @Value("${ai.api.model:deepseek-chat}")
    private String model;

    @Value("${ai.api.max-tokens:8000}")
    private Integer maxTokens;

    @Value("${ai.api.timeout-seconds:90}")
    private Integer timeoutSeconds;

    @Value("${ai.api.claude.key:}")
    private String claudeApiKey;

    @Value("${ai.api.claude.url:https://api.anthropic.com/v1/messages}")
    private String claudeApiUrl;

    @Value("${ai.api.claude.model:claude-opus-4-8}")
    private String claudeModel;

    @Value("${ai.api.claude.max-tokens:8000}")
    private Integer claudeMaxTokens;

    @Value("${ai.api.claude.timeout-seconds:120}")
    private Integer claudeTimeoutSeconds;

    @Value("${ai.api.proxy.host:}")
    private String proxyHost;

    @Value("${ai.api.proxy.port:0}")
    private Integer proxyPort;

    @Value("${ai.api.retry-count:1}")
    private Integer retryCount;

    @PostConstruct
    public void init() {
        this.javaHttpClient = buildJavaHttpClient();
    }

    private java.net.http.HttpClient buildJavaHttpClient() {
        java.net.ProxySelector proxySelector;
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort > 0) {
            log.info("JavaHttpClient uses proxy {}:{}", proxyHost, proxyPort);
            proxySelector = new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(URI uri) {
                    return List.of(new java.net.Proxy(
                            java.net.Proxy.Type.HTTP,
                            new InetSocketAddress(proxyHost, proxyPort)
                    ));
                }

                @Override
                public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                    log.warn("Proxy connect failed to {}: {}", uri, ioe.getMessage());
                }
            };
        } else {
            proxySelector = java.net.ProxySelector.of(null);
        }

        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(proxySelector)
                .build();
    }

    public String generate(String systemPrompt, String userPrompt, String modelChoice) {
        return generate(systemPrompt, userPrompt, modelChoice, systemPrompt);
    }

    public String generate(String systemPrompt,
                           String userPrompt,
                           String modelChoice,
                           String deepSeekFallbackSystemPrompt) {
        if ("claude".equalsIgnoreCase(modelChoice)) {
            try {
                return generateWithClaude(systemPrompt, userPrompt);
            } catch (AiServiceException error) {
                if (shouldFallbackToDeepSeek(error)) {
                    log.warn("Claude unavailable for this request, falling back to DeepSeek with premium prompt: {}",
                            error.getMessage());
                    return generateWithDeepSeek(deepSeekFallbackSystemPrompt, userPrompt);
                }
                throw error;
            }
        }
        return generateWithDeepSeek(systemPrompt, userPrompt);
    }

    public String generate(String systemPrompt, String userPrompt) {
        return generate(systemPrompt, userPrompt, "deepseek");
    }

    private String generateWithDeepSeek(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiServiceException(
                    AiServiceException.Reason.MISCONFIGURED,
                    "AI_API_KEY 未配置，无法生成报告。"
            );
        }
        return executeWithRetry(
                "DeepSeek",
                () -> sendJsonRequest(
                        apiUrl,
                        Map.of(
                                "Authorization", "Bearer " + apiKey,
                                "Content-Type", "application/json"
                        ),
                        buildDeepSeekRequestBody(systemPrompt, userPrompt),
                        timeoutSeconds,
                        "DeepSeek"
                ),
                this::extractDeepSeekContent
        );
    }

    private Map<String, Object> buildDeepSeekRequestBody(String systemPrompt, String userPrompt) {
        return Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
    }

    private String extractDeepSeekContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        throw new AiServiceException(
                AiServiceException.Reason.INVALID_RESPONSE,
                "DeepSeek 返回内容为空或格式异常"
        );
    }

    private String generateWithClaude(String systemPrompt, String userPrompt) {
        if (claudeApiKey == null || claudeApiKey.isBlank()) {
            throw new AiServiceException(
                    AiServiceException.Reason.MISCONFIGURED,
                    "CLAUDE_API_KEY 未配置，无法使用深度解析模型。"
            );
        }
        return executeWithRetry(
                "Opus 4.8",
                () -> sendJsonRequest(
                        claudeApiUrl,
                        Map.of(
                                "x-api-key", claudeApiKey,
                                "anthropic-version", "2023-06-01",
                                "Content-Type", "application/json"
                        ),
                        buildClaudeRequestBody(systemPrompt, userPrompt),
                        claudeTimeoutSeconds,
                        "Claude"
                ),
                this::extractClaudeContent
        );
    }

    private Map<String, Object> buildClaudeRequestBody(String systemPrompt, String userPrompt) {
        return Map.of(
                "model", claudeModel,
                "max_tokens", claudeMaxTokens,
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );
    }

    private String extractClaudeContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode content = root.path("content");
        if (content.isArray() && content.size() > 0) {
            String text = content.get(0).path("text").asText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        throw new AiServiceException(
                AiServiceException.Reason.INVALID_RESPONSE,
                "Opus 4.8 返回内容为空或格式异常"
        );
    }

    private String sendJsonRequest(String url,
                                   Map<String, String> headers,
                                   Object body,
                                   Integer timeoutSecondsValue,
                                   String provider) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSecondsValue == null ? 90 : timeoutSecondsValue));
        headers.forEach(builder::header);
        HttpResponse<String> response = javaHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new AiServiceException(
                    AiServiceException.Reason.UPSTREAM_ERROR,
                    provider + " HTTP " + response.statusCode() + ": " + response.body()
            );
        }
        return response.body();
    }

    private String executeWithRetry(String provider, ThrowingSupplier rawSupplier, ThrowingParser parser) {
        AiServiceException lastError = null;
        int attempts = Math.max(1, retryCount + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String raw = rawSupplier.get();
                return parser.parse(raw);
            } catch (AiServiceException e) {
                lastError = e;
            } catch (Exception e) {
                lastError = mapAiException(provider, e);
            }

            log.warn("{} attempt {}/{} failed: {}", provider, attempt, attempts, lastError.getMessage());
            if (attempt >= attempts || !lastError.isRetryable()) {
                throw lastError;
            }
        }

        throw lastError != null
                ? lastError
                : new AiServiceException(
                        AiServiceException.Reason.UPSTREAM_ERROR,
                        provider + " 服务暂时不可用，请稍后再试。"
                );
    }

    private AiServiceException mapAiException(String provider, Exception error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        if (unwrapped instanceof AiServiceException aiError) {
            return aiError;
        }

        String message = unwrapped.getMessage();
        if (unwrapped instanceof java.util.concurrent.TimeoutException
                || (message != null && message.contains("timed out"))) {
            log.error("{} timeout", provider, unwrapped);
            return new AiServiceException(
                    AiServiceException.Reason.TIMEOUT,
                    provider + " 响应超时，请稍后重试。",
                    unwrapped
            );
        }

        log.error("{} call failed: {}", provider, message, unwrapped);
        return new AiServiceException(
                AiServiceException.Reason.UPSTREAM_ERROR,
                provider + " 服务暂时不可用，请稍后再试。",
                unwrapped
        );
    }

    private boolean shouldFallbackToDeepSeek(AiServiceException error) {
        if (error == null || error.getReason() != AiServiceException.Reason.UPSTREAM_ERROR) {
            return false;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Claude HTTP 403")
                || message.contains("Claude HTTP 429")
                || message.contains("Claude HTTP 500")
                || message.contains("Claude HTTP 502")
                || message.contains("Claude HTTP 503")
                || message.contains("Claude HTTP 504")
                || message.contains("credit balance is too low")
                || message.contains("insufficient credits")
                || message.contains("overloaded_error")
                || message.contains("rate_limit_error");
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingParser {
        String parse(String raw) throws Exception;
    }
}
