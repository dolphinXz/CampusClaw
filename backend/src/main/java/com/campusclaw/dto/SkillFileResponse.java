package com.campusclaw.dto;

import com.campusclaw.domain.AiSkill;
import java.time.Instant;
import java.util.List;

/** 技能文件响应：解析字段 + 原文 + 路由关键词。 */
public record SkillFileResponse(
        Long id,
        String name,
        String description,
        String conditions,
        List<String> keywords,
        String subjectScope,
        String tool,
        boolean enabled,
        String content,
        String rawText,
        Instant updatedAt
) {

    public static SkillFileResponse from(AiSkill skill) {
        return new SkillFileResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getConditions(),
                com.campusclaw.service.SkillFileParser.splitConditions(skill.getConditions()),
                skill.getSubjectScope(),
                skill.getTool(),
                skill.isEnabled(),
                skill.getContent(),
                skill.getRawText(),
                skill.getUpdatedAt()
        );
    }
}
