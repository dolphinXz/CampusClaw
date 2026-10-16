package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/** 修改智能体：字段可选，只更新传入项。 */
public record AgentUpdateRequest(
        @Size(max = 64, message = "智能体名称最多 64 字") String name,
        @Size(max = 2000, message = "角色提示词最多 2000 字") String rolePrompt,
        Boolean enabled
) {
}
