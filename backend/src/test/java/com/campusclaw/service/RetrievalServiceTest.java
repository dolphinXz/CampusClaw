package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.ChunkIndexStatus;
import com.campusclaw.domain.ChunkStrategy;
import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.KnowledgeChunk;
import com.campusclaw.domain.KnowledgeEntry;
import com.campusclaw.domain.Material;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.RetrievalResponse;
import com.campusclaw.gateway.EmbeddingClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.gateway.QdrantClient;
import com.campusclaw.repository.KnowledgeChunkRepository;
import com.campusclaw.repository.MaterialRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 检索服务单测：三种模式、RRF 融合、阈值过滤、班级隔离、故障语义。
 */
class RetrievalServiceTest {

    private KnowledgeChunkRepository chunkRepository;
    private MaterialRepository materialRepository;
    private EmbeddingClient embeddingClient;
    private QdrantClient qdrantClient;
    private RetrievalService service;

    private static final Long CLASS_A = 1L;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KnowledgeChunkRepository.class);
        materialRepository = mock(MaterialRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        qdrantClient = mock(QdrantClient.class);
        service = new RetrievalService(chunkRepository, materialRepository, embeddingClient, qdrantClient);
    }

    private KnowledgeChunk chunk(long id, long materialId, long classId, int index) {
        Material material = new Material(
                new ClassRoom("C", "T"), new User("u", "p", "n", UserRole.TEACHER, new ClassRoom("C", "T")),
                "材料" + materialId, null, "k", "f.txt", "text/plain", 10, "p");
        ReflectionTestUtils.setField(material, "id", materialId);
        KnowledgeEntry entry = new KnowledgeEntry(material, classId, "body");
        ReflectionTestUtils.setField(entry, "id", 100L);
        KnowledgeChunk chunk = new KnowledgeChunk(
                entry, materialId, classId, index, "正文片段" + index, index * 10, index * 10 + 8, ChunkStrategy.AUTO);
        ReflectionTestUtils.setField(chunk, "id", id);
        return chunk;
    }

    @Test
    void emptyQueryReturns400() {
        assertThatThrownBy(() -> service.search("   ", null, null, CLASS_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void noCandidatesReturnsNoneWithFixedMessage() {
        when(chunkRepository.searchKeyword(any(), anyLong(), anyDouble(), anyInt())).thenReturn(List.of());

        RetrievalResponse response = service.search("量子力学", "keyword", null, CLASS_A);

        assertThat(response.hits()).isEmpty();
        assertThat(response.basis()).isEqualTo("none");
        assertThat(response.message()).isEqualTo("资料中未找到相关内容");
    }

    @Test
    void keywordModeDoesNotTouchEmbeddingOrQdrant() {
        KnowledgeChunk chunk = chunk(1L, 10L, CLASS_A, 0);
        KnowledgeChunkRepository.KeywordMatch m1 = match(1L, 3.5);
        when(chunkRepository.searchKeyword(eq("分数"), eq(CLASS_A), anyDouble(), anyInt()))
                .thenReturn(List.of(m1));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(chunk));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(chunk.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("分数", "keyword", null, CLASS_A);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).score().detail().keyword()).isEqualTo(3.5);
        verify(embeddingClient, never()).embed(any());
        verify(qdrantClient, never()).search(any(), anyLong(), anyInt(), anyDouble());
    }

    @Test
    void keywordSurvivesQdrantOutage() {
        // keyword 模式不访问 Qdrant，即使 Qdrant 不可用也不受影响
        when(qdrantClient.search(any(), anyLong(), anyInt(), anyDouble()))
                .thenThrow(new GatewayException("Qdrant 不可用"));
        KnowledgeChunk chunk = chunk(1L, 10L, CLASS_A, 0);
        KnowledgeChunkRepository.KeywordMatch m1 = match(1L, 3.5);
        when(chunkRepository.searchKeyword(eq("分数"), eq(CLASS_A), anyDouble(), anyInt()))
                .thenReturn(List.of(m1));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(chunk));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(chunk.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("分数", "keyword", null, CLASS_A);

        assertThat(response.hits()).hasSize(1);
    }

    @Test
    void vectorModeSortsByCosineAndFiltersClass() {
        KnowledgeChunk chunk = chunk(1L, 10L, CLASS_A, 0);
        when(embeddingClient.embed("同义改写查询")).thenReturn(new float[]{1f, 0f});
        when(qdrantClient.search(any(), eq(CLASS_A), anyInt(), eq(RetrievalService.COSINE_THRESHOLD)))
                .thenReturn(List.of(new QdrantClient.ScoredPoint(1L, 0.62)));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(chunk));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(chunk.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("同义改写查询", "vector", null, CLASS_A);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).score().detail().cosine()).isEqualTo(0.62);
    }

    @Test
    void vectorModeReturns503WhenEmbeddingFails() {
        when(embeddingClient.embed(any())).thenThrow(new GatewayException("嵌入不可用"));

        assertThatThrownBy(() -> service.search("查询", "vector", null, CLASS_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void vectorModeReturns503WhenQdrantDown() {
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f});
        when(qdrantClient.search(any(), anyLong(), anyInt(), anyDouble()))
                .thenThrow(new GatewayException("Qdrant 不可用"));

        assertThatThrownBy(() -> service.search("查询", "vector", null, CLASS_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void backTableDropsHitsFromWrongClass() {
        // Qdrant 命中 1 号切片，但回表时班级不符 → 丢弃（不返回任何他班数据）
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f});
        when(qdrantClient.search(any(), eq(CLASS_A), anyInt(), anyDouble()))
                .thenReturn(List.of(new QdrantClient.ScoredPoint(1L, 0.9)));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of()); // 回表校验过滤后无命中

        RetrievalResponse response = service.search("查询", "vector", null, CLASS_A);

        assertThat(response.hits()).isEmpty();
        assertThat(response.basis()).isEqualTo("none");
    }

    @Test
    void hybridUsesRrfNotScoreAddition() {
        KnowledgeChunk a = chunk(1L, 10L, CLASS_A, 0);
        KnowledgeChunk b = chunk(2L, 11L, CLASS_A, 0);
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f});
        // keyword 路：a(1), b(2)；vector 路：b(1)。RRF：
        // a = 1/(60+1) = 0.01639；b = 1/(60+2) + 1/(60+1) = 0.03252
        KnowledgeChunkRepository.KeywordMatch mk1 = match(1L, 9.9);
        KnowledgeChunkRepository.KeywordMatch mk2 = match(2L, 0.5);
        when(chunkRepository.searchKeyword(eq("查询"), eq(CLASS_A), anyDouble(), anyInt()))
                .thenReturn(List.of(mk1, mk2));
        when(qdrantClient.search(any(), eq(CLASS_A), anyInt(), anyDouble()))
                .thenReturn(List.of(new QdrantClient.ScoredPoint(2L, 0.8)));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(a, b));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(a.getKnowledgeEntry().getMaterial()));
        when(materialRepository.findById(11L)).thenReturn(Optional.of(b.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("查询", "hybrid", null, CLASS_A);

        assertThat(response.hits()).hasSize(2);
        // b 双路命中，RRF 更高；不是把 9.9 + 0.8 相加
        assertThat(response.hits().get(0).chunkId()).isEqualTo(2L);
        assertThat(response.hits().get(0).score().detail().rrf())
                .isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(response.hits().get(1).score().detail().rrf()).isEqualTo(1.0 / 61);
    }

    @Test
    void hybridKeywordOnlyWhenVectorFindsNothing() {
        KnowledgeChunk a = chunk(1L, 10L, CLASS_A, 0);
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f});
        KnowledgeChunkRepository.KeywordMatch m1 = match(1L, 3.0);
        when(chunkRepository.searchKeyword(eq("查询"), eq(CLASS_A), anyDouble(), anyInt()))
                .thenReturn(List.of(m1));
        when(qdrantClient.search(any(), eq(CLASS_A), anyInt(), anyDouble()))
                .thenReturn(List.of()); // 向量路查询成功但无命中：不加该路分数
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(a));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(a.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("查询", "hybrid", null, CLASS_A);

        assertThat(response.hits()).hasSize(1);
        assertThat(response.hits().get(0).score().detail().rrf()).isEqualTo(1.0 / 61);
        assertThat(response.hits().get(0).score().detail().cosine()).isNull();
    }

    @Test
    void hybridReturns503WhenVectorDependencyDown() {
        when(chunkRepository.searchKeyword(any(), anyLong(), anyDouble(), anyInt())).thenReturn(List.of());
        when(embeddingClient.embed(any())).thenThrow(new GatewayException("嵌入不可用"));

        assertThatThrownBy(() -> service.search("查询", "hybrid", null, CLASS_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> service.search("查询", "magic", null, CLASS_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void responseNeverExposesStoragePathOrVector() {
        KnowledgeChunk chunk = chunk(1L, 10L, CLASS_A, 0);
        KnowledgeChunkRepository.KeywordMatch m1 = match(1L, 3.5);
        when(chunkRepository.searchKeyword(eq("分数"), eq(CLASS_A), anyDouble(), anyInt()))
                .thenReturn(List.of(m1));
        when(chunkRepository.findByIdInAndClassIdAndIndexStatus(anyCollection(), eq(CLASS_A), eq(ChunkIndexStatus.READY)))
                .thenReturn(List.of(chunk));
        when(materialRepository.findById(10L)).thenReturn(Optional.of(chunk.getKnowledgeEntry().getMaterial()));

        RetrievalResponse response = service.search("分数", "keyword", null, CLASS_A);
        String json = response.toString();

        assertThat(json).doesNotContain("storageKey");
        assertThat(json).doesNotContain("vector");
        assertThat(response.hits().get(0).openUrl()).isEqualTo("/api/materials/10/content");
    }

    private KnowledgeChunkRepository.KeywordMatch match(long id, double relevance) {
        KnowledgeChunkRepository.KeywordMatch match = mock(KnowledgeChunkRepository.KeywordMatch.class);
        when(match.getId()).thenReturn(id);
        when(match.getRelevance()).thenReturn(relevance);
        return match;
    }
}
