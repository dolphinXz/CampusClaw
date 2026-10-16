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

/** 解题会话：归属单个用户与班级，锁定一个智能体；跨用户/跨班访问与「不存在」一致。 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 会话锁定的智能体编号；换智能体须新建会话。 */
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected ChatSession() {
    }

    public ChatSession(Long classId, Long ownerId, Long agentId, String title) {
        this.classId = classId;
        this.ownerId = ownerId;
        this.agentId = agentId;
        this.title = title;
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

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String title) {
        this.title = title;
    }

    /** 触碰更新时间（会话列表按最近更新倒序）。 */
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
