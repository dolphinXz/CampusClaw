package com.campusclaw.service;

import com.campusclaw.domain.SystemPrompt;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.PromptResponse;
import com.campusclaw.repository.SystemPromptRepository;
import com.campusclaw.security.CampusUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 教师系统提示词：每班一条（get-or-create）。
 * 教师可保存、学生 403；每轮回答从这里读当前值，保存后自然从下一轮生效。
 */
@Service
public class PromptService {

    /** 未保存时的内置默认提示词（服务端常量，不依赖前端配置）。 */
    public static final String DEFAULT_PROMPT = """
            你是本班的解题助手，服务对象是本班学生。
            只依据提供的本班课程材料回答；引用材料时使用 [1]、[2] 等编号，编号与材料列表顺序一致。
            材料中没有依据时如实说明，不要编造材料中不存在的内容。
            """;

    private final SystemPromptRepository promptRepository;

    public PromptService(SystemPromptRepository promptRepository) {
        this.promptRepository = promptRepository;
    }

    /** 读取本班提示词；从未保存过则返回内置默认值（不写库）。 */
    @Transactional
    public PromptResponse get(Long classId) {
        return promptRepository.findByClassId(classId)
                .map(prompt -> new PromptResponse(prompt.getContent(), false, prompt.getUpdatedAt()))
                .orElseGet(() -> new PromptResponse(DEFAULT_PROMPT, true, null));
    }

    /** 教师保存本班提示词（upsert）。 */
    @Transactional
    public PromptResponse save(Long classId, String content, CampusUserPrincipal principal) {
        if (principal.getRole() != UserRole.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有教师可以修改提示词");
        }
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提示词内容不能为空");
        }
        SystemPrompt prompt = promptRepository.findByClassId(classId)
                .orElseGet(() -> new SystemPrompt(classId, trimmed, principal.getId()));
        prompt.updateContent(trimmed, principal.getId());
        prompt = promptRepository.save(prompt);
        return new PromptResponse(prompt.getContent(), false, prompt.getUpdatedAt());
    }

    /** 供问答链路读取当前生效提示词文本（有保存用保存，否则用默认）。 */
    @Transactional(readOnly = true)
    public String effectiveContent(Long classId) {
        return promptRepository.findByClassId(classId)
                .map(SystemPrompt::getContent)
                .orElse(DEFAULT_PROMPT);
    }
}
