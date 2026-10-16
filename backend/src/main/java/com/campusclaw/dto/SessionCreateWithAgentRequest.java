package com.campusclaw.dto;

import jakarta.validation.constraints.NotNull;

/** 新建会话：必须选定本班已启用智能体（否则 404）；标题可缺省「新的提问」。 */
public record SessionCreateWithAgentRequest(
        @NotNull(message = "必须选定智能体") Long agentId,
        String title
) {
}
