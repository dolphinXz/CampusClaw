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

/** 教师系统提示词：每班唯一一条，规定助手身份与整体行为。 */
@Entity
@Table(name = "system_prompts")
public class SystemPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false, unique = true)
    private Long classId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected SystemPrompt() {
    }

    public SystemPrompt(Long classId, String content, Long updatedBy) {
        this.classId = classId;
        this.content = content;
        this.updatedBy = updatedBy;
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

    public String getContent() {
        return content;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateContent(String content, Long updatedBy) {
        this.content = content;
        this.updatedBy = updatedBy;
    }
}
