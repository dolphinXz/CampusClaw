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
 * 智能体：班内名称唯一，规定「助手是谁（角色提示词）、能用什么技能、能查什么材料」。
 * 会话只锁定智能体编号，绑定变更对已有会话下一轮立即生效。
 */
@Entity
@Table(name = "assistants")
public class Assistant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 角色提示词；空值时运行时回退服务端默认角色。 */
    @Column(name = "role_prompt", columnDefinition = "TEXT")
    private String rolePrompt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected Assistant() {
    }

    public Assistant(Long classId, String name, String rolePrompt, boolean enabled, Long updatedBy) {
        this.classId = classId;
        this.name = name;
        this.rolePrompt = rolePrompt;
        this.enabled = enabled;
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

    public String getName() {
        return name;
    }

    public String getRolePrompt() {
        return rolePrompt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void apply(String name, String rolePrompt, Boolean enabled, Long updatedBy) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (rolePrompt != null) {
            this.rolePrompt = rolePrompt.isBlank() ? null : rolePrompt;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        this.updatedBy = updatedBy;
    }
}
