package com.campusclaw.web;

import com.campusclaw.dto.RetrievalRequest;
import com.campusclaw.dto.RetrievalResponse;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口。class_id 只从已认证会话获得——请求体中的任何班级编号字段被忽略（DTO 不定义）。
 */
@Tag(name = "检索", description = "本班知识库关键字 / 向量 / 混合检索")
@RestController
@RequestMapping("/api")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Operation(summary = "知识库检索", description = "mode=keyword|vector|hybrid（默认 hybrid）；班级取自会话，伪造 classId 被忽略；空查询 400，向量依赖故障 503。")
    @PostMapping("/retrieval")
    public RetrievalResponse search(
            @Valid @RequestBody RetrievalRequest request,
            Authentication authentication
    ) {
        Long classId = SecurityUtils.currentPrincipal(authentication).getClassId();
        return retrievalService.search(request.query(), request.mode(), request.topK(), classId);
    }
}
