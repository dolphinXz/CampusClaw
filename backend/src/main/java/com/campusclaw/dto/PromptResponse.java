package com.campusclaw.dto;

import java.time.Instant;

/** 提示词响应；default=true 表示尚未保存过、当前为内置默认值。 */
public record PromptResponse(String content, boolean isDefault, Instant updatedAt) {
}
