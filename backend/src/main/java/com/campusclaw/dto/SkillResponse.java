package com.campusclaw.dto;

import com.campusclaw.domain.AiSkill;
import java.time.Instant;

/** 技能响应。内容与启用状态由服务端管理，学生只读。 */
public record SkillResponse(
        Long id,
        String code,
        String name,
        String description,
        String content,
        boolean enabled,
        Instant updatedAt
) {

    public static SkillResponse from(AiSkill skill) {
        return new SkillResponse(
                skill.getId(),
                skill.getCode(),
                skill.getName(),
                skill.getDescription(),
                skill.getContent(),
                skill.isEnabled(),
                skill.getUpdatedAt()
        );
    }
}
