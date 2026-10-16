package com.campusclaw.dto;

import java.util.List;

/**
 * 问答响应。回答中的 [n] 引用编号对应 citation.refNumber（[1] ↔ refNumber=1）。
 * 无依据时 answer 为固定句、citations 为空。
 */
public record AskResponse(String answer, String basis, List<Citation> citations) {

    public static final String NO_EVIDENCE_MESSAGE = "资料中未找到相关内容";

    /**
     * 引用条目。refNumber 为该材料在回答中的 [n] 编号：
     * /api/ask 为检索名次（1..n 全量）；会话问答只保留回答中实际引用的材料（编号可能不连续）。
     */
    public record Citation(
            Long materialId,
            String materialTitle,
            Long chunkId,
            int chunkIndex,
            String excerpt,
            int refNumber
    ) {
    }
}
