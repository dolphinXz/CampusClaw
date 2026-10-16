package com.campusclaw.dto;

import java.util.List;

/** 绑定回显：管理页勾选回填。 */
public record AgentBindingsResponse(List<BoundSkill> skills, List<BoundMaterial> materials) {

    public record BoundSkill(Long id, String name, boolean enabled) {
    }

    public record BoundMaterial(Long id, String title) {
    }
}
