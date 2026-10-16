package com.campusclaw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 提示词保存请求：1～8000 字，服务端只存不执行拼接。 */
public record PromptRequest(
        @NotBlank(message = "提示词内容不能为空")
        @Size(max = 8000, message = "提示词最多 8000 字")
        String content
) {
}
