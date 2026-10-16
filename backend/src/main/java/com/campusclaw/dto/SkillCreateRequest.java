package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建技能请求（教师）：code 全班唯一（小写字母开头，只含小写字母、数字与短横线），
 * name 与 content 必填；description 可选；新建即启用。
 */
public record SkillCreateRequest(
        @Size(max = 31, message = "技能代码最多 31 字符") String code,
        @Size(max = 100, message = "技能名称最多 100 字") String name,
        @Size(max = 500, message = "技能说明最多 500 字") String description,
        @Size(max = 8000, message = "技能内容最多 8000 字") String content
) {
}
