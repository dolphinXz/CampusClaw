package com.campusclaw.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 对话网关客户端（OpenAI 兼容 /chat/completions）。
 * 系统提示由服务端生成，模型无法自行访问数据库、Qdrant 或他班资料。
 * 支持非流式 complete 与流式 stream（stream:true，增量回调后返回全文）。
 */
@Component
public class ChatClient {

    /** 一条对话消息。role 只允许 system/user/assistant。 */
    public record Message(String role, String content) {
    }

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public ChatClient(
            @Value("${app.retrieval.chat-base-url}") String baseUrl,
            @Value("${app.retrieval.chat-api-key}") String apiKey,
            @Value("${app.retrieval.chat-model}") String model,
            ObjectMapper objectMapper
    ) {
        RestClient.Builder builder = RestClient.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
    }

    /** 生成简短回答。失败抛 GatewayException，不泄漏密钥与上游响应。 */
    @SuppressWarnings("unchecked")
    public String complete(List<Message> messages) {
        List<Map<String, String>> payload = new ArrayList<>();
        for (Message message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/chat/completions")
                    .body(Map.of(
                            "model", model,
                            "messages", payload,
                            "temperature", 0.2
                    ))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new GatewayException("对话网关返回空响应");
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new GatewayException("对话网关响应缺少回答");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null) {
                throw new GatewayException("对话网关响应缺少正文");
            }
            return String.valueOf(content).trim();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("对话调用失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 流式生成：POST stream:true，逐行解析 SSE 的 data: 增量并回调 onDelta，
     * 返回累计全文。data: [DONE] 结束；任何异常抛 GatewayException（不含密钥与上游原文）。
     */
    public String stream(List<Message> messages, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        try {
            String bodyJson = buildStreamBody(messages);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new GatewayException("对话网关返回 " + response.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String delta = extractDelta(data);
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                }
            }
            return full.toString().trim();
        } catch (GatewayException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("对话流被中断", e);
        } catch (Exception e) {
            throw new GatewayException("对话流调用失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private String buildStreamBody(List<Message> messages) throws Exception {
        List<Map<String, String>> payload = new ArrayList<>();
        for (Message message : messages) {
            payload.add(Map.of("role", message.role(), "content", message.content()));
        }
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", payload,
                "temperature", 0.2,
                "stream", true
        ));
    }

    /** 从一条 SSE data JSON 中取出 choices[0].delta.content；解析失败返回 null（跳过该行）。 */
    private String extractDelta(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() ? null : delta.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
