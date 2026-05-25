package com.zodiac.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.netty.http.client.HttpClient;

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

    private WebClient webClient;
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

    @Value("${ai.api.claude.model:claude-opus-4-7}")
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
        this.webClient = buildWebClient(timeoutSeconds, "DeepSeek");
        this.javaHttpClient = buildJavaHttpClient();
    }

    private WebClient buildWebClient(int timeoutSecs, String name) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(timeoutSecs));
        // 显式不启用系统代理(ALL_PROXY 等),避免走 ClashX 代理被 403
        httpClient = httpClient.noProxy();
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort > 0) {
            log.info("{} WebClient uses proxy {}:{}", name, proxyHost, proxyPort);
            httpClient = httpClient.proxy(proxy -> proxy
                    .type(reactor.netty.transport.ProxyProvider.Proxy.HTTP)
                    .address(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    private java.net.http.HttpClient buildJavaHttpClient() {
        java.net.ProxySelector proxySelector;
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort > 0) {
            log.info("JavaHttpClient uses proxy {}:{}", proxyHost, proxyPort);
            proxySelector = new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(URI uri) {
                    return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new InetSocketAddress(proxyHost, proxyPort)));
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
        if ("claude".equalsIgnoreCase(modelChoice)) {
            return generateWithClaude(systemPrompt, userPrompt);
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
                () -> webClient.post()
                        .uri(apiUrl)
                        .headers(headers -> headers.setBearerAuth(apiKey))
                        .bodyValue(buildDeepSeekRequestBody(systemPrompt, userPrompt))
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block(),
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
        throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, "DeepSeek 返回内容为空或格式异常。");
    }


    private String generateWithClaude(String systemPrompt, String userPrompt) {
        if (claudeApiKey == null || claudeApiKey.isBlank()) {
            throw new AiServiceException(
                    AiServiceException.Reason.MISCONFIGURED,
                    "CLAUDE_API_KEY 未配置，无法使用深度解析模型。"
            );
        }
        return executeWithRetry(
                "Opus 4.7",
                () -> {
                    String body = objectMapper.writeValueAsString(buildClaudeRequestBody(systemPrompt, userPrompt));
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(claudeApiUrl))
                            .header("x-api-key", claudeApiKey)
                            .header("anthropic-version", "2023-06-01")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(Duration.ofSeconds(claudeTimeoutSeconds))
                            .build();
                    HttpResponse<String> response = javaHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 400) {
                        throw new AiServiceException(
                                AiServiceException.Reason.UPSTREAM_ERROR,
                                "Claude HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                },
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
        throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, "Opus 4.7 返回内容为空或格式异常。");
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
                : new AiServiceException(AiServiceException.Reason.UPSTREAM_ERROR, provider + " 服务暂时不可用，请稍后再试。");
    }

    private AiServiceException mapAiException(String provider, Exception error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        if (unwrapped instanceof AiServiceException aiError) {
            return aiError;
        }

        String message = unwrapped.getMessage();
        if (unwrapped instanceof java.util.concurrent.TimeoutException
                || (message != null && message.contains("Did not observe any item or terminal signal within"))) {
            log.error("{} timeout", provider, unwrapped);
            return new AiServiceException(
                    AiServiceException.Reason.TIMEOUT,
                    provider + " 响应超时，请稍后重试。",
                    unwrapped
            );
        }

        String body = "";
        if (unwrapped instanceof WebClientResponseException wcre) {
            body = wcre.getResponseBodyAsString();
        }
        log.error("{} call failed, status={}, body={}", provider,
                unwrapped instanceof WebClientResponseException wcre2 ? wcre2.getStatusCode() : "?", body);
        return new AiServiceException(
                AiServiceException.Reason.UPSTREAM_ERROR,
                provider + " 服务暂时不可用，请稍后再试。",
                unwrapped
        );
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
