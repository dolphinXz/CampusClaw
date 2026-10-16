package com.campusclaw.repository;

import com.campusclaw.domain.ChunkIndexStatus;
import com.campusclaw.domain.KnowledgeChunk;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByMaterialIdAndClassIdOrderByChunkIndex(Long materialId, Long classId);

    /** 按班级删除某材料旧切片（重建索引时先删后建）。 */
    void deleteByMaterialIdAndClassId(Long materialId, Long classId);

    /** 向量命中回表：班级与 ready 状态双重过滤，不一致的命中直接不返回。 */
    @Query("""
            select c from KnowledgeChunk c
            where c.id in :ids and c.classId = :classId and c.indexStatus = :status
            """)
    List<KnowledgeChunk> findByIdInAndClassIdAndIndexStatus(
            @Param("ids") Collection<Long> ids,
            @Param("classId") Long classId,
            @Param("status") ChunkIndexStatus status
    );

    /** 中文全文检索：ngram 全文索引 + 会话班级 + ready 状态，按相关度降序。 */
    @Query(
            value = """
                    select c.id, match(c.chunk_text) against (:query in natural language mode) as relevance
                    from knowledge_chunks c
                    where c.class_id = :classId
                      and c.index_status = 'READY'
                      and match(c.chunk_text) against (:query in natural language mode) > :minRelevance
                    order by relevance desc
                    limit :limit
                    """,
            nativeQuery = true
    )
    List<KeywordMatch> searchKeyword(
            @Param("query") String query,
            @Param("classId") Long classId,
            @Param("minRelevance") double minRelevance,
            @Param("limit") int limit
    );

    /** 智能体限定检索：额外限定材料白名单（绑定材料集合）。 */
    @Query(
            value = """
                    select c.id, match(c.chunk_text) against (:query in natural language mode) as relevance
                    from knowledge_chunks c
                    where c.class_id = :classId
                      and c.index_status = 'READY'
                      and c.material_id in (:materialIds)
                      and match(c.chunk_text) against (:query in natural language mode) > :minRelevance
                    order by relevance desc
                    limit :limit
                    """,
            nativeQuery = true
    )
    List<KeywordMatch> searchKeywordInMaterials(
            @Param("query") String query,
            @Param("classId") Long classId,
            @Param("minRelevance") double minRelevance,
            @Param("limit") int limit,
            @Param("materialIds") Collection<Long> materialIds
    );

    interface KeywordMatch {
        Long getId();

        Double getRelevance();
    }
}
