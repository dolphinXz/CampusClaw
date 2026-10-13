package com.campusclaw.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 对话网关客户端（OpenAI 兼容 /chat/completions）。
 * 系统提示由服务端生成，模型无法自行访问数据库、Qdrant 或他班资料。
 */
@Component
public class ChatClient {

    /** 一条对话消息。role 只允许 system/user/assistant。 */
    public record Message(String role, String content) {
    }

    private final RestClient restClient;
    private final String model;

    public ChatClient(
            @Value("${app.retrieval.chat-base-url}") String baseUrl,
            @Value("${app.retrieval.chat-api-key}") String apiKey,
            @Value("${app.retrieval.chat-model}") String model
    ) {
        RestClient.Builder builder = RestClient.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
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
}
