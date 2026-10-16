package com.campusclaw.dto;

import jakarta.validation.constraints.Size;

/** 新建会话请求：标题可选，缺省用首个问题生成。 */
public record SessionCreateRequest(
        @Size(max = 100, message = "会话标题最多 100 字") String title
) {
}
