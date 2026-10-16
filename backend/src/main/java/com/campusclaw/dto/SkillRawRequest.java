package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/** 技能文件请求：只收原文，服务端解析。 */
public record SkillRawRequest(
        @Size(max = 8000, message = "技能文件总长度不能超过 8000 字") String rawText
) {
}
