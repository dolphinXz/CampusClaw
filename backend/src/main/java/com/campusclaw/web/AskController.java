package com.campusclaw.web;

import com.campusclaw.dto.AskRequest;
import com.campusclaw.dto.AskResponse;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.AskService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 有依据才生成的问答接口：先混合检索本班前 4 条切片，无依据短路固定回答。
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request, Authentication authentication) {
        Long classId = SecurityUtils.currentPrincipal(authentication).getClassId();
        return askService.ask(request, classId);
    }
}
