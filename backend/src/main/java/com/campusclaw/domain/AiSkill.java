package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * AI 技能（文件式）：教师上传/编辑单一文本文件，服务端解析为字段并保留原文。
 * 旧的 code 字段保留用于存量行兼容，新建技能不再要求。
 */
@Entity
@Table(name = "ai_skills")
public class AiSkill {

    public static final String CODE_SOLUTION_GUIDE = "solution-guide";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 适用条件原文（关键词切词前）。 */
    @Column(name = "conditions", nullable = false, length = 120)
    private String conditions;

    /** 资料范围（学科词），空串表示不限。 */
    @Column(name = "subject_scope", nullable = false, length = 32)
    private String subjectScope;

    /** 受校验的工具声明，只允许 knowledge_search。 */
    @Column(name = "tool", nullable = false, length = 32)
    private String tool;

    /** 技能文件原文，供教师取回编辑。 */
    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected AiSkill() {
    }

    public AiSkill(
            Long classId, String code, String name, String description,
            String content, boolean enabled, String conditions, String subjectScope,
            String tool, String rawText
    ) {
        this.classId = classId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.content = content;
        this.enabled = enabled;
        this.conditions = conditions == null ? "" : conditions;
        this.subjectScope = subjectScope == null ? "" : subjectScope;
        this.tool = tool == null ? "knowledge_search" : tool;
        this.rawText = rawText;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getConditions() {
        return conditions;
    }

    public String getSubjectScope() {
        return subjectScope;
    }

    public String getTool() {
        return tool;
    }

    public String getRawText() {
        return rawText;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 用解析后的文件字段全量覆盖（文件式技能没有增量 patch 语义）。 */
    public void replaceFromFile(
            String name, String description, String content, boolean enabled,
            String conditions, String subjectScope, String tool, String rawText, Long updatedBy
    ) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.enabled = enabled;
        this.conditions = conditions == null ? "" : conditions;
        this.subjectScope = subjectScope == null ? "" : subjectScope;
        this.tool = tool;
        this.rawText = rawText;
        this.updatedBy = updatedBy;
    }
}
