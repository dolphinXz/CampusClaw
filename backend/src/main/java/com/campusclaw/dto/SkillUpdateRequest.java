package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/** 技能更新请求：字段均可选，只更新传入的非空字段。 */
public record SkillUpdateRequest(
        @Size(max = 100, message = "技能名称最多 100 字") String name,
        @Size(max = 500, message = "技能说明最多 500 字") String description,
        @Size(max = 8000, message = "技能内容最多 8000 字") String content,
        Boolean enabled
) {
}
