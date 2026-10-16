package com.campusclaw.web;

import com.campusclaw.dto.SessionCreateWithAgentRequest;
import com.campusclaw.dto.SessionDetailResponse;
import com.campusclaw.dto.SessionResponse;
import com.campusclaw.domain.UserRole;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解题会话：新建必须选定本班已启用智能体；只操作本人会话；
 * 他人/他班与不存在统一 404。classId 只取自认证主体。
 */
@Tag(name = "会话", description = "会话锁定智能体；只操作本人会话，越权与不存在均 404")
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(
            summary = "新建会话",
            description = "必须选定本班已启用智能体（他班/不存在/未启用 → 404）；标题缺省「新的提问」。"
    )
    @PostMapping
    public SessionResponse create(
            @Valid @RequestBody SessionCreateWithAgentRequest request,
            Authentication authentication
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        boolean studentView = principal.getRole() != UserRole.TEACHER;
        return sessionService.create(
                request.agentId(), request.title(), principal.getId(), principal.getClassId(), studentView);
    }

    @Operation(summary = "我的会话列表", description = "只含本人会话，按最近更新时间倒序，含消息数。")
    @GetMapping
    public List<SessionResponse> list(Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return sessionService.list(principal.getId());
    }

    @Operation(summary = "会话详情与消息", description = "按序返回全部消息与引用（含锁定的智能体）；他人/他班 404。")
    @GetMapping("/{id}")
    public SessionDetailResponse detail(@PathVariable("id") Long id, Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return sessionService.detail(id, principal.getId());
    }
}
