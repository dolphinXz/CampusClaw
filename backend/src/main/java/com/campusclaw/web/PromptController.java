package com.campusclaw.web;

import com.campusclaw.dto.PromptRequest;
import com.campusclaw.dto.PromptResponse;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.PromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师提示词：读取任意已登录可用，保存仅教师（服务层 + 注解双校验）。
 * classId 只取自认证主体，请求体无班级字段可写。
 */
@Tag(name = "提示词", description = "教师维护本班系统提示词；学生只读。保存后从下一轮对话生效")
@RestController
@RequestMapping("/api/prompt")
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @Operation(summary = "读取提示词", description = "任意已登录用户；从未保存时返回内置默认值（isDefault=true）。")
    @GetMapping
    public PromptResponse get(Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return promptService.get(principal.getClassId());
    }

    @Operation(summary = "保存提示词（教师）", description = "1～8000 字；学生 403；空内容 400。")
    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping
    public PromptResponse put(
            @Valid @RequestBody PromptRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return promptService.save(principal.getClassId(), request.content(), principal);
    }
}
