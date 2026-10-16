package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 会话消息：user / assistant；assistant 行记录检索依据、引用 JSON 与生效技能代码（可追溯）。 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** 检索命中：回答有依据。 */
    public static final String BASIS_FOUND = "found";
    /** 检索未命中：固定句回答，无引用。 */
    public static final String BASIS_NONE = "none";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "basis", length = 16)
    private String basis;

    /** 引用 JSON 数组；普通列绑定（@Lob 会按 Clob 写入 JSON 列导致 22001）。 */
    @Column(name = "citations", columnDefinition = "JSON")
    private String citations;

    @Column(name = "skill_code", length = 64)
    private String skillCode;

    /** 命中技能名（文件式技能，无 code 时用名称记录）。 */
    @Column(name = "skill_name", length = 32)
    private String skillName;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(Long sessionId, Long classId, String role, String content) {
        this.sessionId = sessionId;
        this.classId = classId;
        this.role = role;
        this.content = content;
    }

    public static ChatMessage assistant(
            Long sessionId,
            Long classId,
            String content,
            String basis,
            String citationsJson,
            String skillCode
    ) {
        ChatMessage message = new ChatMessage(sessionId, classId, ROLE_ASSISTANT, content);
        message.basis = basis;
        message.citations = citationsJson;
        message.skillCode = skillCode;
        return message;
    }

    /** 智能体链路的助手消息：命中技能名可空（未命中技能）。 */
    public static ChatMessage assistantWithSkill(
            Long sessionId,
            Long classId,
            String content,
            String basis,
            String citationsJson,
            String skillName
    ) {
        ChatMessage message = assistant(sessionId, classId, content, basis, citationsJson, null);
        message.skillName = skillName;
        return message;
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

    public Long getSessionId() {
        return sessionId;
    }

    public Long getClassId() {
        return classId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getBasis() {
        return basis;
    }

    public String getCitations() {
        return citations;
    }

    public String getSkillCode() {
        return skillCode;
    }

    public String getSkillName() {
        return skillName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
