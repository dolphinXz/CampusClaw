package com.campusclaw.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Qdrant REST 客户端：只存向量和关联编号，不存正文。
 * point ID = knowledge_chunks.id，payload 的 chunk_id 同值。
 */
@Component
public class QdrantClient {

    private final RestClient restClient;
    private final String collection;

    public QdrantClient(
            @Value("${app.retrieval.qdrant-url}") String baseUrl,
            @Value("${app.retrieval.qdrant-collection}") String collection
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.collection = collection;
    }

    /** 启动校验读取 collection 信息：向量维度与距离；collection 不存在时抛异常。 */
    @SuppressWarnings("unchecked")
    public CollectionInfo collectionInfo() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/collections/{name}", collection)
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> result = body == null ? null : (Map<String, Object>) body.get("result");
            if (result == null) {
                throw new GatewayException("Qdrant 集合不存在：" + collection);
            }
            Map<String, Object> config = (Map<String, Object>) result.get("config");
            Map<String, Object> params = config == null ? null : (Map<String, Object>) config.get("params");
            Map<String, Object> vectors = params == null ? null : (Map<String, Object>) params.get("vectors");
            int size = vectors == null ? -1 : ((Number) vectors.get("size")).intValue();
            String distance = vectors == null ? null : String.valueOf(vectors.get("distance"));
            return new CollectionInfo(size, distance);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Qdrant 不可用：" + e.getClass().getSimpleName(), e);
        }
    }

    /** collection 不存在时按配置创建（余弦距离，维度 = EMBEDDING_DIM）。 */
    @SuppressWarnings("unchecked")
    public void ensureCollection(int dimension) {
        try {
            collectionInfo();
        } catch (GatewayException missing) {
            try {
                restClient.put()
                        .uri("/collections/{name}", collection)
                        .body(Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                throw new GatewayException("创建 Qdrant 集合失败：" + e.getClass().getSimpleName(), e);
            }
        }
    }

    /** 写入单条向量（幂等 upsert，point id = chunk id）。 */
    public void upsertPoint(long chunkId, float[] vector, Map<String, Object> payload) {
        try {
            restClient.put()
                    .uri("/collections/{name}/points", collection)
                    .body(Map.of("points", List.of(Map.of(
                            "id", chunkId,
                            "vector", toList(vector),
                            "payload", payload
                    ))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new GatewayException("Qdrant 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 删除指定 point（重建索引时清理旧向量）。 */
    public void deletePoints(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/collections/{name}/points/delete", collection)
                    .body(Map.of("points", ids))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new GatewayException("Qdrant 删除失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 向量检索：按班级过滤，余弦降序。
     * 返回 (chunkId, score) 列表；正文与班级最终以 MySQL 回表为准。
     */
    @SuppressWarnings("unchecked")
    public List<ScoredPoint> search(float[] vector, long classId, int limit, double minScore) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/collections/{name}/points/search", collection)
                    .body(Map.of(
                            "vector", toList(vector),
                            "filter", Map.of("must", List.of(Map.of(
                                    "key", "class_id",
                                    "match", Map.of("value", classId)
                            ))),
                            "limit", limit,
                            "score_threshold", minScore
                    ))
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("result") == null) {
                return List.of();
            }
            List<Map<String, Object>> result = (List<Map<String, Object>>) body.get("result");
            List<ScoredPoint> points = new ArrayList<>();
            for (Map<String, Object> item : result) {
                long id = ((Number) item.get("id")).longValue();
                double score = ((Number) item.get("score")).doubleValue();
                points.add(new ScoredPoint(id, score));
            }
            return points;
        } catch (Exception e) {
            throw new GatewayException("Qdrant 检索失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    public record CollectionInfo(int size, String distance) {
    }

    public record ScoredPoint(long chunkId, double score) {
    }

    /** payload 构造：只放关联编号，不放正文。 */
    public static Map<String, Object> payload(long classId, long materialId, long entryId, long chunkId, int chunkIndex) {
        Map<String, Object> map = new HashMap<>();
        map.put("class_id", classId);
        map.put("material_id", materialId);
        map.put("knowledge_entry_id", entryId);
        map.put("chunk_id", chunkId);
        map.put("chunk_index", chunkIndex);
        return map;
    }
}
