package com.campusclaw.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 嵌入网关客户端（OpenAI 兼容 /embeddings）。只在服务端调用；
 * 密钥只从环境变量注入，不进响应与日志。
 */
@Component
public class EmbeddingClient {

    private final RestClient restClient;
    private final String model;
    private final int dimension;

    public EmbeddingClient(
            @Value("${app.retrieval.embedding-base-url}") String baseUrl,
            @Value("${app.retrieval.embedding-api-key}") String apiKey,
            @Value("${app.retrieval.embedding-model}") String model,
            @Value("${app.retrieval.embedding-dim}") int dimension
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
        this.dimension = dimension;
    }

    public int getDimension() {
        return dimension;
    }

    /** 对单段文本生成向量。失败抛 GatewayException，不泄漏上游响应原文。 */
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/embeddings")
                    .body(Map.of("model", model, "input", List.of(text)))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new GatewayException("嵌入网关返回空响应");
            }
            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
            if (data == null || data.isEmpty() || data.get(0).get("embedding") == null) {
                throw new GatewayException("嵌入网关响应缺少向量数据");
            }
            List<Number> values = (List<Number>) data.get(0).get("embedding");
            if (values.size() != dimension) {
                throw new GatewayException(
                        "嵌入维度不一致：配置 " + dimension + "，实际 " + values.size());
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i).floatValue();
            }
            return vector;
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            // 日志/响应不带密钥；只回传失败类别
            throw new GatewayException("嵌入调用失败：" + e.getClass().getSimpleName(), e);
        }
    }
}
