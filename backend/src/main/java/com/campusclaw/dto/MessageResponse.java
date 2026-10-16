package com.campusclaw.dto;

import com.campusclaw.domain.ChatMessage;
import java.time.Instant;
import java.util.List;

/**
 * 会话消息。assistant 行带检索依据、引用与命中技能名（可追溯）；
 * 引用结构与 /api/ask 的 Citation 一致。skillName 为空表示该轮未命中技能。
 */
public record MessageResponse(
        Long id,
        String role,
        String content,
        String basis,
        List<AskResponse.Citation> citations,
        String skillName,
        Instant createdAt
) {

    public static MessageResponse from(ChatMessage message, List<AskResponse.Citation> citations) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getBasis(),
                citations,
                message.getSkillName(),
                message.getCreatedAt()
        );
    }
}
