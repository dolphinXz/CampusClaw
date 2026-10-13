package com.campusclaw.dto;

import java.util.List;

/**
 * 问答响应。回答中的 [1]、[2] 引用编号与 citations 数组顺序一一对应（[1] ↔ citations[0]）。
 * 无依据时 answer 为固定句、citations 为空。
 */
public record AskResponse(String answer, String basis, List<Citation> citations) {

    public static final String NO_EVIDENCE_MESSAGE = "资料中未找到相关内容";

    public record Citation(
            Long materialId,
            String materialTitle,
            Long chunkId,
            int chunkIndex,
            String excerpt
    ) {
    }
}
