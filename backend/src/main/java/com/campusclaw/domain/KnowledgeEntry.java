package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 材料正文条目：1 材料 ↔ 1 正文，保存解析后的原始正文（预处理不改写本列）。 */
@Entity
@Table(name = "knowledge_entries")
public class KnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, unique = true)
    private Material material;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Lob
    @Column(name = "body_text", nullable = false, columnDefinition = "LONGTEXT")
    private String bodyText;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected KnowledgeEntry() {
    }

    public KnowledgeEntry(Material material, Long classId, String bodyText) {
        this.material = material;
        this.classId = classId;
        this.bodyText = bodyText;
        this.charCount = bodyText.length();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    public Long getClassId() {
        return classId;
    }

    public String getBodyText() {
        return bodyText;
    }

    public int getCharCount() {
        return charCount;
    }
}
