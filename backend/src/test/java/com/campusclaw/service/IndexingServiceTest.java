package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.campusclaw.gateway.EmbeddingClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.gateway.QdrantClient;
import com.campusclaw.repository.KnowledgeChunkRepository;
import com.campusclaw.repository.KnowledgeEntryRepository;
import com.campusclaw.repository.MaterialRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 索引流水线失败语义：嵌入失败材料仍在、切片 failed、不写半截向量、可重建恢复。
 */
class IndexingServiceTest {

    private MaterialRepository materialRepository;
    private KnowledgeEntryRepository entryRepository;
    private KnowledgeChunkRepository chunkRepository;
    private MaterialParserService parserService;
    private MaterialStorageService storageService;
    private EmbeddingClient embeddingClient;
    private QdrantClient qdrantClient;
    private IndexingService service;

    private Material material;
    private static final Long CLASS_A = 1L;

    @BeforeEach
    void setUp() throws Exception {
        materialRepository = mock(MaterialRepository.class);
        entryRepository = mock(KnowledgeEntryRepository.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        parserService = new MaterialParserService();
        storageService = mock(MaterialStorageService.class);
        embeddingClient = mock(EmbeddingClient.class);
        qdrantClient = mock(QdrantClient.class);
        service = new IndexingService(
                materialRepository, entryRepository, chunkRepository,
                parserService, storageService,
                new TextPreprocessor(), new ChunkingService(),
                embeddingClient, qdrantClient);

        ClassRoom classRoom = new ClassRoom("CLASS_A", "A 班");
        ReflectionTestUtils.setField(classRoom, "id", CLASS_A);
        material = new Material(classRoom,
                new User("t", "p", "n", UserRole.TEACHER, classRoom),
                "教案", null, "key", "lesson.txt", "text/plain", 100, "p");
        ReflectionTestUtils.setField(material, "id", 5L);

        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));
        when(storageService.read("key")).thenReturn("分数加减法要先通分。同分母分数直接加减。".getBytes());
        when(entryRepository.save(any())).thenAnswer((InvocationOnMock invocation) -> {
            KnowledgeEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", 200L);
            return entry;
        });
    }

    private void stubChunkSave() {
        when(chunkRepository.saveAll(any())).thenAnswer((InvocationOnMock invocation) -> {
            @SuppressWarnings("unchecked")
            List<KnowledgeChunk> chunks = invocation.getArgument(0);
            List<KnowledgeChunk> saved = new ArrayList<>();
            for (KnowledgeChunk chunk : chunks) {
                ReflectionTestUtils.setField(chunk, "id", (long) (saved.size() + 1));
                saved.add(chunk);
            }
            return saved;
        });
    }

    @Test
    void embeddingFailureKeepsMaterialAndMarksChunksFailed() {
        stubChunkSave();
        when(embeddingClient.embed(anyString())).thenThrow(new GatewayException("嵌入不可用"));

        service.indexMaterial(5L, CLASS_A);

        // 材料与正文条目仍在（未回滚）
        verify(materialRepository, never()).delete(any());
        verify(entryRepository).save(any());
        // 失败切片被保存为 failed
        verify(chunkRepository).save(argThatChunkStatus(ChunkIndexStatus.FAILED));
        // 未写入任何向量（不写半截向量）
        verify(qdrantClient, never()).upsertPoint(anyLong(), any(), anyMap());
    }

    @Test
    void successMarksReadyAndUpserts() {
        stubChunkSave();
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{1f, 2f});

        service.indexMaterial(5L, CLASS_A);

        // point ID = chunk id；payload 含五个关联编号字段
        verify(qdrantClient).upsertPoint(eq(1L), any(), org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> payload) ->
                        payload.containsKey("class_id")
                                && payload.containsKey("material_id")
                                && payload.containsKey("knowledge_entry_id")
                                && payload.containsKey("chunk_id")
                                && payload.containsKey("chunk_index")));
        verify(chunkRepository).save(argThatChunkStatus(ChunkIndexStatus.READY));
    }

    @Test
    void rebuildDeletesOldChunksAndVectorsFirst() {
        stubChunkSave();
        KnowledgeChunk old = new KnowledgeChunk(
                new KnowledgeEntry(material, CLASS_A, "body"), 5L, CLASS_A, 0, "旧切片", 0, 3, ChunkStrategy.AUTO);
        ReflectionTestUtils.setField(old, "id", 99L);
        when(chunkRepository.findByMaterialIdAndClassIdOrderByChunkIndex(5L, CLASS_A)).thenReturn(List.of(old));
        when(entryRepository.findByMaterialIdAndClassId(5L, CLASS_A)).thenReturn(Optional.empty());
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{1f});

        service.rebuild(5L, CLASS_A, ChunkStrategy.AUTO, null);

        // 旧切片先删、旧向量清理，再生成新索引
        verify(chunkRepository).deleteByMaterialIdAndClassId(5L, CLASS_A);
        verify(qdrantClient).deletePoints(List.of(99L));
        verify(qdrantClient).upsertPoint(eq(1L), any(), anyMap());
    }

    @Test
    void qdrantWriteFailureMarksFailedNotReady() {
        stubChunkSave();
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{1f});
        org.mockito.Mockito.doThrow(new GatewayException("Qdrant 不可用"))
                .when(qdrantClient).upsertPoint(anyLong(), any(), anyMap());

        service.indexMaterial(5L, CLASS_A);

        verify(chunkRepository).save(argThatChunkStatus(ChunkIndexStatus.FAILED));
    }

    private KnowledgeChunk argThatChunkStatus(ChunkIndexStatus status) {
        return org.mockito.ArgumentMatchers.argThat(chunk -> chunk.getIndexStatus() == status);
    }
}
