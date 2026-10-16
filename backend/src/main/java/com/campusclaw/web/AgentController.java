package com.campusclaw.web;

import com.campusclaw.dto.AgentBindingsRequest;
import com.campusclaw.dto.AgentBindingsResponse;
import com.campusclaw.dto.AgentCreateRequest;
import com.campusclaw.dto.AgentResponse;
import com.campusclaw.dto.AgentUpdateRequest;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体管理：教师写、学生只读（只见已启用）；
 * 他班/不存在/对学生不可见（未启用）统一 404。classId 只取自认证主体。
 */
@Tag(name = "智能体", description = "教师维护智能体与技能/材料绑定；学生只见已启用")
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @Operation(summary = "智能体列表", description = "教师看本班全部（含停用）；学生只见已启用。")
    @GetMapping
    public List<AgentResponse> list(Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return agentService.list(principal.getClassId(), principal);
    }

    @Operation(summary = "新建智能体（教师）", description = "名称班内唯一 ≤64 字；角色提示词 ≤2000 字，空用默认角色。")
    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping
    public AgentResponse create(
            @Valid @RequestBody AgentCreateRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return agentService.create(request.name(), request.rolePrompt(), principal.getClassId(), principal);
    }

    @Operation(summary = "智能体详情", description = "学生访问未启用/他班/不存在均 404。")
    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable("id") Long id, Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return agentService.get(id, principal.getClassId(), principal);
    }

    @Operation(summary = "修改智能体（教师）", description = "字段可选；名称重复 400；他班/不存在 404。")
    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}")
    public AgentResponse update(
            @PathVariable("id") Long id,
            @Valid @RequestBody AgentUpdateRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return agentService.update(id, request.name(), request.rolePrompt(), request.enabled(),
                principal.getClassId(), principal);
    }

    @Operation(summary = "绑定技能（教师）", description = "全量替换；任一 id 非本班 → 400 且不产生部分绑定。")
    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}/skills")
    public void replaceSkills(
            @PathVariable("id") Long id,
            @RequestBody AgentBindingsRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        agentService.replaceSkillBindings(id, request.ids(), principal.getClassId(), principal);
    }

    @Operation(summary = "绑定材料（教师）", description = "全量替换；任一 id 非本班 → 400。空绑定时该智能体会话无知识库依据。")
    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}/materials")
    public void replaceMaterials(
            @PathVariable("id") Long id,
            @RequestBody AgentBindingsRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        agentService.replaceMaterialBindings(id, request.ids(), principal.getClassId(), principal);
    }

    @Operation(summary = "绑定回显（教师）", description = "管理页勾选回填。")
    @PreAuthorize("hasRole('TEACHER')")
    @GetMapping("/{id}/bindings")
    public AgentBindingsResponse bindings(@PathVariable("id") Long id, Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return agentService.bindings(id, principal.getClassId(), principal);
    }
}
