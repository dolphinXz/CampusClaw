package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/** 新建智能体：名称必填 ≤64 字；角色提示词可选 ≤2000 字（空→默认角色）。 */
public record AgentCreateRequest(
        @Size(max = 64, message = "智能体名称最多 64 字") String name,
        @Size(max = 2000, message = "角色提示词最多 2000 字") String rolePrompt
) {
}
