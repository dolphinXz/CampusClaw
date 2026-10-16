package com.campusclaw.dto;

import java.util.List;

/** 全量替换绑定：id 列表整体覆盖；任一 id 非本班 → 400（不产生部分绑定）。 */
public record AgentBindingsRequest(List<Long> ids) {
}
