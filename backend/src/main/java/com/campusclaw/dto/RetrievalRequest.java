package com.campusclaw.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 检索请求。class_id 只从会话获得，DTO 中不定义该字段——伪造的班级编号无处安放。
 */
public record RetrievalRequest(
        @NotBlank(message = "查询不能为空") String query,
        String mode,   // keyword | vector | hybrid，缺省 hybrid
        Integer topK   // 缺省 10，上限 50
) {
}
