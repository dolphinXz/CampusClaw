package com.campusclaw.service;

import com.campusclaw.domain.ChunkIndexStatus;
import com.campusclaw.domain.KnowledgeChunk;
import com.campusclaw.domain.Material;
import com.campusclaw.dto.RetrievalResponse;
import com.campusclaw.dto.RetrievalResponse.Hit;
import com.campusclaw.gateway.EmbeddingClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.gateway.QdrantClient;
import com.campusclaw.repository.KnowledgeChunkRepository;
import com.campusclaw.repository.KnowledgeChunkRepository.KeywordMatch;
import com.campusclaw.repository.MaterialRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 三种检索模式：
 * - keyword：只查 MySQL 全文索引，不调嵌入、不访问 Qdrant，Qdrant 不可用时照常工作
 * - vector：问句嵌入 → Qdrant 按班级过滤 → 回 MySQL 校验班级与 ready 取正文，余弦降序
 * - hybrid（默认）：两路各按绝对阈值过滤后 RRF 融合，k=60；某路未命中不加该路分；
 *   向量路依赖故障（嵌入/Qdrant 不可用）→ 503
 *
 * 全文分与余弦分含义不同，禁止直接相加；RRF 只使用名次。
 * class_id 一律取自会话，三处查询（keyword SQL、Qdrant filter、回表）全部带班级条件。
 */
@Service
public class RetrievalService {

    public static final String MODE_KEYWORD = "keyword";
    public static final String MODE_VECTOR = "vector";
    public static final String MODE_HYBRID = "hybrid";

    /**
     * keyword 全文相关度绝对阈值：严格大于 0 即命中。
     * ngram 语料下相关度量级约 1e-8，0 分行是未命中（见 design 勘误）。
     */
    public static final double KEYWORD_MIN_RELEVANCE = 0.0;
    /** vector 余弦绝对阈值（课件值）。 */
    public static final double COSINE_THRESHOLD = 0.35;
    /** RRF 常数。 */
    public static final int RRF_K = 60;

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;

    private final KnowledgeChunkRepository chunkRepository;
    private final MaterialRepository materialRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    public RetrievalService(
            KnowledgeChunkRepository chunkRepository,
            MaterialRepository materialRepository,
            EmbeddingClient embeddingClient,
            QdrantClient qdrantClient
    ) {
        this.chunkRepository = chunkRepository;
        this.materialRepository = materialRepository;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    /**
     * 检索入口。query 去空白后为空 → 400；
     * 过滤后无候选 → 200 + 空 hits + 固定无依据提示，不拿低相关切片充数。
     */
    public RetrievalResponse search(String rawQuery, String mode, Integer topK, Long classId) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询不能为空");
        }
        String effectiveMode = normalizeMode(mode);
        int limit = normalizeTopK(topK);

        List<Scored> candidates;
        try {
            candidates = switch (effectiveMode) {
                case MODE_KEYWORD -> keywordPath(query, classId, limit);
                case MODE_VECTOR -> vectorPath(query, classId, limit);
                case MODE_HYBRID -> hybridPath(query, classId, limit);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知检索模式");
            };
        } catch (GatewayException e) {
            // 向量路依赖故障（嵌入或 Qdrant 不可用）→ 503，不返回部分或编造结果
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "检索服务暂不可用");
        }

        if (candidates.isEmpty()) {
            return new RetrievalResponse(
                    effectiveMode,
                    RetrievalResponse.BASIS_NONE,
                    RetrievalResponse.NO_EVIDENCE_MESSAGE,
                    List.of()
            );
        }

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Scored scored = candidates.get(i);
            hits.add(Hit.of(
                    scored.chunk(),
                    scored.materialTitle(),
                    effectiveMode,
                    i + 1,
                    scored.keywordScore(),
                    scored.cosineScore(),
                    scored.rrfScore()
            ));
        }
        return new RetrievalResponse(effectiveMode, RetrievalResponse.BASIS_FOUND, null, hits);
    }

    /** hybrid 取前 n 条（/api/ask 复用同一检索方式取前 4 条）。 */
    public List<Scored> topHits(String query, int n, Long classId) {
        List<Scored> all = hybridPath(query.trim(), classId, Math.max(n, 1));
        return all.size() <= n ? all : all.subList(0, n);
    }

    // ---------- 三条路径 ----------

    private List<Scored> keywordPath(String query, Long classId, int limit) {
        List<KeywordMatch> matches = chunkRepository.searchKeyword(
                query, classId, KEYWORD_MIN_RELEVANCE, limit);
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> relevanceById = new LinkedHashMap<>();
        for (KeywordMatch match : matches) {
            relevanceById.put(match.getId(), match.getRelevance());
        }
        List<KnowledgeChunk> chunks = loadReadyChunks(relevanceById.keySet(), classId);
        return chunks.stream()
                .filter(c -> relevanceById.containsKey(c.getId())) // 只保留该路命中的切片
                .map(c -> Scored.keyword(c, relevanceById.get(c.getId()), titleOf(c)))
                .sorted(Comparator.comparingDouble((Scored s) -> s.keywordScore()).reversed())
                .limit(limit)
                .toList();
    }

    private List<Scored> vectorPath(String query, Long classId, int limit) {
        float[] vector = embed(query);           // 嵌入失败 → 503
        List<QdrantClient.ScoredPoint> points = qdrantClient.search(   // Qdrant 不可用 → 503
                vector, classId, limit, COSINE_THRESHOLD);

        Map<Long, Double> cosineById = new LinkedHashMap<>();
        for (QdrantClient.ScoredPoint point : points) {
            if (point.score() >= COSINE_THRESHOLD) {
                cosineById.put(point.chunkId(), point.score());
            }
        }
        if (cosineById.isEmpty()) {
            return List.of();
        }
        // 回表：再次带会话班级 + ready 校验，不一致的命中直接丢弃
        List<KnowledgeChunk> chunks = loadReadyChunks(cosineById.keySet(), classId);
        return chunks.stream()
                .filter(c -> cosineById.containsKey(c.getId())) // 只保留该路命中的切片
                .map(c -> Scored.vector(c, cosineById.get(c.getId()), titleOf(c)))
                .sorted(Comparator.comparingDouble((Scored s) -> s.cosineScore()).reversed())
                .limit(limit)
                .toList();
    }

    private List<Scored> hybridPath(String query, Long classId, int limit) {
        List<Scored> keyword;
        try {
            keyword = keywordPath(query, classId, limit);
        } catch (RuntimeException e) {
            // 关键字路径只有数据库故障才会异常，沿用 503 语义
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "检索服务暂不可用");
        }

        List<Scored> vector;
        try {
            vector = vectorPath(query, classId, limit);
        } catch (GatewayException e) {
            // 向量路依赖故障（嵌入或 Qdrant 不可用）→ 503，不降级为纯关键字
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "检索服务暂不可用");
        }

        // RRF 融合：只用名次；某路未命中（空列表）时不加该路分数
        Map<Long, MutableScore> merged = new LinkedHashMap<>();
        Map<Long, KnowledgeChunk> chunkById = new HashMap<>();
        Map<Long, String> titles = new HashMap<>();

        accumulate(merged, chunkById, titles, keyword, true);
        accumulate(merged, chunkById, titles, vector, false);

        return merged.values().stream()
                .map(m -> new Scored(
                        chunkById.get(m.chunkId),
                        titles.getOrDefault(m.chunkId, ""),
                        m.keywordScore,
                        m.cosineScore,
                        m.rrfScore))
                .sorted(Comparator.comparingDouble((Scored s) -> s.rrfScore()).reversed()
                        .thenComparingLong(s -> s.chunk().getId()))
                .limit(limit)
                .toList();
    }

    private void accumulate(
            Map<Long, MutableScore> merged,
            Map<Long, KnowledgeChunk> chunkById,
            Map<Long, String> titles,
            List<Scored> path,
            boolean isKeyword
    ) {
        for (int i = 0; i < path.size(); i++) {
            Scored scored = path.get(i);
            long id = scored.chunk().getId();
            MutableScore entry = merged.computeIfAbsent(id, k -> new MutableScore(id));
            // rank 从 1 起：score += 1/(k + rank)
            entry.rrfScore += 1.0 / (RRF_K + (i + 1));
            if (isKeyword) {
                entry.keywordScore = scored.keywordScore();
            } else {
                entry.cosineScore = scored.cosineScore();
            }
            chunkById.put(id, scored.chunk());
            titles.put(id, scored.materialTitle());
        }
    }

    // ---------- 辅助 ----------

    private float[] embed(String query) {
        try {
            return embeddingClient.embed(query);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("嵌入调用失败", e);
        }
    }

    /** 回表加载：必须同时满足 会话班级 + ready 状态。 */
    private List<KnowledgeChunk> loadReadyChunks(java.util.Collection<Long> ids, Long classId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findByIdInAndClassIdAndIndexStatus(ids, classId, ChunkIndexStatus.READY);
    }

    private String titleOf(KnowledgeChunk chunk) {
        return materialRepository.findById(chunk.getMaterialId())
                .map(Material::getTitle)
                .orElse("");
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_HYBRID;
        }
        return switch (mode.trim().toLowerCase()) {
            case MODE_KEYWORD -> MODE_KEYWORD;
            case MODE_VECTOR -> MODE_VECTOR;
            case MODE_HYBRID -> MODE_HYBRID;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知检索模式：" + mode);
        };
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        return Math.max(1, Math.min(topK, MAX_TOP_K));
    }

    /** 单条候选：两路原始分 + 融合分，可空（该路未命中）。 */
    public record Scored(
            KnowledgeChunk chunk,
            String materialTitle,
            Double keywordScore,
            Double cosineScore,
            Double rrfScore
    ) {
        static Scored keyword(KnowledgeChunk chunk, double score, String title) {
            return new Scored(chunk, title, score, null, null);
        }

        static Scored vector(KnowledgeChunk chunk, double score, String title) {
            return new Scored(chunk, title, null, score, null);
        }
    }

    private static final class MutableScore {
        final long chunkId;
        double rrfScore;
        Double keywordScore;
        Double cosineScore;

        MutableScore(long chunkId) {
            this.chunkId = chunkId;
        }
    }
}
