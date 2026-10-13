package com.campusclaw.config;

import com.campusclaw.gateway.EmbeddingClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.gateway.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 启动校验：Qdrant collection 存在、向量维度等于 EMBEDDING_DIM、距离为余弦；
 * 不符则启动失败并打印可读错误（不含密钥）。
 * collection 不存在时按配置创建。
 * Qdrant 完全不可达时降级启动（告警）：关键字检索照常工作，向量/混合检索运行期返回 503。
 */
@Component
public class QdrantStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(QdrantStartupValidator.class);

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;

    public QdrantStartupValidator(QdrantClient qdrantClient, EmbeddingClient embeddingClient) {
        this.qdrantClient = qdrantClient;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public void afterPropertiesSet() {
        int expectedDim = embeddingClient.getDimension();
        QdrantClient.CollectionInfo info;
        try {
            qdrantClient.ensureCollection(expectedDim);
            info = qdrantClient.collectionInfo();
        } catch (GatewayException e) {
            // 不可降级为“配置不符”：Qdrant 不可用时 keyword 模式必须仍可工作
            log.warn("Qdrant 不可用（{}）：向量/混合检索将返回 503，关键字检索不受影响", e.getMessage());
            return;
        }
        if (info.size() != expectedDim) {
            throw new IllegalStateException(
                    "Qdrant 向量维度不一致：配置 " + expectedDim + "，collection " + info.size());
        }
        if (info.distance() != null && !info.distance().equalsIgnoreCase("cosine")) {
            throw new IllegalStateException("Qdrant 距离应为 Cosine，实际为 " + info.distance());
        }
        log.info("Qdrant collection 校验通过：维度 {}，距离 Cosine", expectedDim);
    }
}
