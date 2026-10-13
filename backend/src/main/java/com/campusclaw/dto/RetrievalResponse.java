package com.campusclaw.dto;

import com.campusclaw.domain.KnowledgeChunk;
import java.util.List;

/**
 * 统一可追溯检索结果。
 * 只含派生字段：不含存储路径、原始向量、失败原因、他班任何数据。
 */
public record RetrievalResponse(
        String mode,
        String basis,          // found | none
        String message,        // 仅 basis=none 时出现：固定无依据提示
        List<Hit> hits
) {

    public static final String BASIS_FOUND = "found";
    public static final String BASIS_NONE = "none";
    public static final String NO_EVIDENCE_MESSAGE = "资料中未找到相关内容";

    public record Hit(
            Long materialId,
            String materialTitle,
            Long chunkId,
            int chunkIndex,
            CharRange charRange,
            String excerpt,
            Score score,
            String openUrl
    ) {
        public static Hit of(
                KnowledgeChunk chunk,
                String materialTitle,
                String source,
                int rank,
                Double keywordScore,
                Double cosineScore,
                Double rrfScore
        ) {
            String text = chunk.getChunkText();
            String excerpt = text.length() > 300 ? text.substring(0, 300) : text;
            return new Hit(
                    chunk.getMaterialId(),
                    materialTitle,
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    new CharRange(chunk.getStartOffset(), chunk.getEndOffset(), "chunk-text"),
                    excerpt,
                    new Score(source, rank, new Detail(keywordScore, cosineScore, rrfScore)),
                    "/api/materials/" + chunk.getMaterialId() + "/content"
            );
        }
    }

    /** 字符范围基于切分文本（预处理后），不冒充原文件下标。 */
    public record CharRange(int start, int end, String basis) {
    }

    public record Score(String source, int rank, Detail detail) {
    }

    /** 各路原始分：含义不同，禁止相加；融合只用 RRF。 */
    public record Detail(Double keyword, Double cosine, Double rrf) {
    }
}
