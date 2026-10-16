package com.campusclaw.web;

import com.campusclaw.dto.SkillFileResponse;
import com.campusclaw.dto.SkillRawRequest;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文件式技能：写操作仅教师；解析错误返回可读 400；删除级联解绑。 */
@Tag(name = "技能", description = "文件式技能：元数据区 + 空行 + 说明正文；写操作仅教师")
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @Operation(summary = "技能列表", description = "含解析字段与文件原文。")
    @GetMapping
    public List<SkillFileResponse> list(Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return skillService.list(principal.getClassId());
    }

    @Operation(
            summary = "上传技能文件（教师）",
            description = "body={rawText}；解析失败返回 400 与可读原因；班内名称唯一。"
    )
    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping
    public SkillFileResponse create(
            @Valid @RequestBody SkillRawRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return skillService.create(request.rawText(), principal.getClassId(), principal);
    }

    @Operation(summary = "更新技能文件（教师）", description = "body={rawText} 重解析全量覆盖；他班/不存在 404。")
    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}")
    public SkillFileResponse update(
            @PathVariable("id") Long id,
            @Valid @RequestBody SkillRawRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return skillService.update(id, request.rawText(), principal.getClassId(), principal);
    }

    @Operation(summary = "删除技能（教师）", description = "同步删除该技能的全部智能体绑定。")
    @PreAuthorize("hasRole('TEACHER')")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id, Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        skillService.delete(id, principal.getClassId(), principal);
    }
}
