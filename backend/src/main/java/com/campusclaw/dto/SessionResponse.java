package com.campusclaw.dto;

import com.campusclaw.domain.ChatSession;
import java.time.Instant;

/** 会话摘要：只含本人会话，不含 classId 等内部字段。 */
public record SessionResponse(
        Long id,
        String title,
        int messageCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static SessionResponse from(ChatSession session, int messageCount) {
        return new SessionResponse(
                session.getId(),
                session.getTitle(),
                messageCount,
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
