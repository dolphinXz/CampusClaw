package com.campusclaw.service;

import com.campusclaw.domain.ChunkIndexStatus;
import com.campusclaw.domain.ChunkStrategy;
import com.campusclaw.domain.KnowledgeChunk;
import com.campusclaw.domain.KnowledgeEntry;
import com.campusclaw.domain.Material;
import com.campusclaw.gateway.EmbeddingClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.gateway.QdrantClient;
import com.campusclaw.repository.KnowledgeChunkRepository;
import com.campusclaw.repository.KnowledgeEntryRepository;
import com.campusclaw.repository.MaterialRepository;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 索引流水线：保存正文 → 切分写 MySQL（pending）→ 嵌入 → 写 Qdrant → ready/failed。
 *
 * 失败边界（与上传事务区分）：材料与正文保存成功后，
 * 嵌入或 Qdrant 失败不回滚材料，只把切片置 failed，不写半截向量，失败切片不参与检索。
 * 已入库材料不会因配置变化自动重切，只在教师显式重建时更新。
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final MaterialRepository materialRepository;
    private final KnowledgeEntryRepository entryRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MaterialParserService parserService;
    private final MaterialStorageService storageService;
    private final TextPreprocessor preprocessor;
    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    public IndexingService(
            MaterialRepository materialRepository,
            KnowledgeEntryRepository entryRepository,
            KnowledgeChunkRepository chunkRepository,
            MaterialParserService parserService,
            MaterialStorageService storageService,
            TextPreprocessor preprocessor,
            ChunkingService chunkingService,
            EmbeddingClient embeddingClient,
            QdrantClient qdrantClient
    ) {
        this.materialRepository = materialRepository;
        this.entryRepository = entryRepository;
        this.chunkRepository = chunkRepository;
        this.parserService = parserService;
        this.storageService = storageService;
        this.preprocessor = preprocessor;
        this.chunkingService = chunkingService;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    /**
     * 为材料建立索引（上传后触发，或补索引）。
     * 材料保存成功后调用本方法，任何失败都不影响材料本身。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void indexMaterial(Long materialId, Long classId) {
        index(materialId, classId, ChunkStrategy.AUTO, null);
    }

    /** 教师显式重建：先删旧切片与旧向量，再按本次策略生成新索引，不残留失效主键。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void rebuild(Long materialId, Long classId, ChunkStrategy strategy, ChunkingService.CustomParams params) {
        index(materialId, classId, strategy, params);
    }

    private void index(Long materialId, Long classId, ChunkStrategy strategy, ChunkingService.CustomParams params) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "材料不存在"));
        if (!Objects.equals(material.getClassRoom().getId(), classId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材料不存在");
        }

        // 解析正文真相源：body_text 用完整解析结果，不改写
        String bodyText = parserService.parseFull(material.getContentType(), readBytes(material));
        if (bodyText == null || bodyText.isBlank()) {
            log.info("材料 {} 无可解析正文，跳过切分", materialId);
            return;
        }

        // 先删旧切片与旧向量（幂等：首次索引时是空操作）
        clearExisting(materialId, classId);

        KnowledgeEntry entry = entryRepository.save(new KnowledgeEntry(material, classId, bodyText));

        // 切分只作用于预处理副本；偏移为预处理后文本位置
        String processed = preprocessor.process(bodyText);
        List<ChunkingService.Chunk> chunks = chunkingService.chunk(processed, strategy, params);
        if (chunks.isEmpty()) {
            return;
        }

        List<KnowledgeChunk> pending = chunks.stream()
                .map(c -> new KnowledgeChunk(
                        entry, materialId, classId, c.index(), c.text(), c.start(), c.end(), strategy))
                .toList();
        pending = chunkRepository.saveAll(pending);

        // 逐片：先嵌入成功、再 upsert 成功才置 ready；失败置 failed 且不触碰 Qdrant
        for (KnowledgeChunk chunk : pending) {
            try {
                float[] vector = embeddingClient.embed(chunk.getChunkText());
                qdrantClient.upsertPoint(
                        chunk.getId(),
                        vector,
                        QdrantClient.payload(classId, materialId, entry.getId(), chunk.getId(), chunk.getChunkIndex())
                );
                chunk.markReady();
            } catch (GatewayException e) {
                chunk.markFailed(e.getMessage());
                log.warn("切片 {} 向量化失败：{}", chunk.getId(), e.getMessage());
            }
            chunkRepository.save(chunk);
        }
    }

    /** 删除该材料旧切片、旧向量与正文条目。 */
    @Transactional
    public void clearExisting(Long materialId, Long classId) {
        List<KnowledgeChunk> old = chunkRepository.findByMaterialIdAndClassIdOrderByChunkIndex(materialId, classId);
        if (!old.isEmpty()) {
            List<Long> ids = old.stream().map(KnowledgeChunk::getId).collect(Collectors.toList());
            chunkRepository.deleteByMaterialIdAndClassId(materialId, classId);
            try {
                qdrantClient.deletePoints(ids);
            } catch (GatewayException e) {
                // Qdrant 不可用时旧向量残留在库里，但回表会按 class_id + ready 过滤掉，不会被检索到
                log.warn("清理旧向量失败（不影响删除切片）：{}", e.getMessage());
            }
        }
        entryRepository.findByMaterialIdAndClassId(materialId, classId)
                .ifPresent(entryRepository::delete);
        // 立即落库删除，避免同事务后续插入新条目时撞 uk_knowledge_entries_material 唯一键
        entryRepository.flush();
    }

    /** 材料级索引状态汇总（供重建接口返回）。 */
    @Transactional(readOnly = true)
    public IndexSummary summary(Long materialId, Long classId) {
        List<KnowledgeChunk> chunks = chunkRepository.findByMaterialIdAndClassIdOrderByChunkIndex(materialId, classId);
        long ready = chunks.stream().filter(c -> c.getIndexStatus() == ChunkIndexStatus.READY).count();
        long failed = chunks.stream().filter(c -> c.getIndexStatus() == ChunkIndexStatus.FAILED).count();
        return new IndexSummary(materialId, chunks.size(), ready, failed);
    }

    private byte[] readBytes(Material material) {
        try {
            return storageService.read(material.getStorageKey());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材料文件不存在");
        }
    }

    public record IndexSummary(Long materialId, int total, long ready, long failed) {
    }
}
