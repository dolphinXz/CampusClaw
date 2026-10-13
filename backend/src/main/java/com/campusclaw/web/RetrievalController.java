package com.campusclaw.web;

import com.campusclaw.dto.RetrievalRequest;
import com.campusclaw.dto.RetrievalResponse;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.RetrievalService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口。class_id 只从已认证会话获得——请求体中的任何班级编号字段被忽略（DTO 不定义）。
 */
@RestController
@RequestMapping("/api")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/retrieval")
    public RetrievalResponse search(
            @Valid @RequestBody RetrievalRequest request,
            Authentication authentication
    ) {
        Long classId = SecurityUtils.currentPrincipal(authentication).getClassId();
        return retrievalService.search(request.query(), request.mode(), request.topK(), classId);
    }
}
