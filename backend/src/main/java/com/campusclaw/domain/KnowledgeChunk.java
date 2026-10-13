package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 可检索切片。id 同时是 Qdrant point ID 与 payload 中的 chunk_id。
 * start/end 偏移是预处理后切分文本中的字符区间。
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_entry_id", nullable = false)
    private KnowledgeEntry knowledgeEntry;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Lob
    @Column(name = "chunk_text", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String chunkText;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 16)
    private ChunkIndexStatus indexStatus;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 16)
    private ChunkStrategy strategy;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(
            KnowledgeEntry knowledgeEntry,
            Long materialId,
            Long classId,
            int chunkIndex,
            String chunkText,
            int startOffset,
            int endOffset,
            ChunkStrategy strategy
    ) {
        this.knowledgeEntry = knowledgeEntry;
        this.materialId = materialId;
        this.classId = classId;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.strategy = strategy;
        this.indexStatus = ChunkIndexStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** 向量写入成功后置 ready。 */
    public void markReady() {
        this.indexStatus = ChunkIndexStatus.READY;
        this.failureReason = null;
    }

    /** 嵌入或 Qdrant 写入失败：置 failed 并记录原因（不含密钥与上游响应原文）。 */
    public void markFailed(String reason) {
        this.indexStatus = ChunkIndexStatus.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public Long getId() {
        return id;
    }

    public KnowledgeEntry getKnowledgeEntry() {
        return knowledgeEntry;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public Long getClassId() {
        return classId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getChunkText() {
        return chunkText;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public ChunkIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public ChunkStrategy getStrategy() {
        return strategy;
    }
}
