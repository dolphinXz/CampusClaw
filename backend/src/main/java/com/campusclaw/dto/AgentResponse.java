package com.campusclaw.dto;

import com.campusclaw.domain.Assistant;
import java.time.Instant;

/** 智能体响应。rolePrompt 为空时客户端展示「使用默认角色」。 */
public record AgentResponse(
        Long id,
        String name,
        String rolePrompt,
        boolean enabled,
        Instant updatedAt
) {

    public static AgentResponse from(Assistant assistant) {
        return new AgentResponse(
                assistant.getId(),
                assistant.getName(),
                assistant.getRolePrompt(),
                assistant.isEnabled(),
                assistant.getUpdatedAt()
        );
    }
}
