package com.campusclaw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 发送消息请求：只接受问题文本；身份、班级、system 消息、引用一律不接受客户端传入。 */
public record MessageSendRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 4000, message = "问题最多 4000 字")
        String question
) {
}
