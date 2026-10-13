package com.campusclaw.service;

import com.campusclaw.domain.Material;
import com.campusclaw.repository.KnowledgeEntryRepository;
import com.campusclaw.repository.MaterialRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 历史/种子材料补索引：对尚无 knowledge_entries 的材料以 auto 策略完成切分与向量化。
 * 幂等——已有索引的材料跳过；配置变化不会自动重切（重切只在教师显式重建时发生）。
 */
@Component
@Order(100) // 在 SeedDataService 之后执行
public class IndexBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBackfillRunner.class);

    private final MaterialRepository materialRepository;
    private final KnowledgeEntryRepository entryRepository;
    private final IndexingService indexingService;

    public IndexBackfillRunner(
            MaterialRepository materialRepository,
            KnowledgeEntryRepository entryRepository,
            IndexingService indexingService
    ) {
        this.materialRepository = materialRepository;
        this.entryRepository = entryRepository;
        this.indexingService = indexingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Material> materials = materialRepository.findAll();
        int backfilled = 0;
        for (Material material : materials) {
            Long classId = material.getClassRoom().getId();
            if (entryRepository.existsByMaterialIdAndClassId(material.getId(), classId)) {
                continue;
            }
            try {
                indexingService.indexMaterial(material.getId(), classId);
                backfilled++;
            } catch (RuntimeException e) {
                log.warn("材料 {} 补索引失败：{}", material.getId(), e.getMessage());
            }
        }
        if (backfilled > 0) {
            log.info("历史材料补索引完成：{} 条", backfilled);
        }
    }
}
