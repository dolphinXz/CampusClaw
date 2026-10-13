package com.campusclaw.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 问答请求。history 只帮助理解追问（最多 4 条），检索只使用最新 question。
 * 客户端提交的 system 消息会被丢弃，DTO 中只接受 user/assistant。
 */
public record AskRequest(
        @NotBlank(message = "问题不能为空") String question,
        List<HistoryMessage> history
) {

    public record HistoryMessage(String role, String content) {
    }
}
