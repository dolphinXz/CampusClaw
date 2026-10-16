package com.campusclaw.dto;

import java.time.Instant;
import java.util.List;

/** 会话详情：含锁定的智能体与按序消息（刷新后可恢复）。 */
public record SessionDetailResponse(
        Long id,
        String title,
        Instant createdAt,
        Long agentId,
        String agentName,
        List<MessageResponse> messages
) {
}
