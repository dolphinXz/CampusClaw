package com.campusclaw.service;

import com.campusclaw.domain.AiSkill;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.SkillFileResponse;
import com.campusclaw.repository.AiSkillRepository;
import com.campusclaw.security.CampusUserPrincipal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文件式技能管理：教师上传/编辑单一文本文件，服务端解析后持久化并保留原文。
 * 解析错误返回面向教师的可读 400；写操作仅教师；删除技能级联解绑（FK ON DELETE CASCADE）。
 */
@Service
public class SkillService {

    private final AiSkillRepository skillRepository;

    public SkillService(AiSkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Transactional(readOnly = true)
    public List<SkillFileResponse> list(Long classId) {
        return skillRepository.findByClassIdOrderById(classId).stream()
                .map(SkillFileResponse::from)
                .toList();
    }

    @Transactional
    public SkillFileResponse create(String rawText, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        SkillFileParser.ParsedSkill parsed = parseOrThrow(rawText);
        if (skillRepository.existsByClassIdAndName(classId, parsed.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本班已有同名技能：" + parsed.name());
        }
        AiSkill skill = new AiSkill(
                classId, null, parsed.name(), summarize(parsed.body()), parsed.body(), parsed.enabled(),
                parsed.conditions(), parsed.subjectScope(), parsed.tool(), parsed.rawText());
        return SkillFileResponse.from(skillRepository.save(skill));
    }

    @Transactional
    public SkillFileResponse update(Long id, String rawText, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        AiSkill skill = skillRepository.findByIdAndClassId(id, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在"));
        SkillFileParser.ParsedSkill parsed = parseOrThrow(rawText);
        if (skillRepository.existsByClassIdAndNameAndIdNot(classId, parsed.name(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本班已有同名技能：" + parsed.name());
        }
        skill.replaceFromFile(parsed.name(), summarize(parsed.body()), parsed.body(), parsed.enabled(),
                parsed.conditions(), parsed.subjectScope(), parsed.tool(), parsed.rawText(), principal.getId());
        return SkillFileResponse.from(skillRepository.save(skill));
    }

    /** 删除技能；绑定关系由外键 ON DELETE CASCADE 同步删除。 */
    @Transactional
    public void delete(Long id, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        AiSkill skill = skillRepository.findByIdAndClassId(id, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在"));
        skillRepository.delete(skill);
    }

    /** 问答链路读取绑定候选时按 id 取技能（编排层再按绑定过滤）。 */
    @Transactional(readOnly = true)
    public AiSkill requireInClass(Long id, Long classId) {
        return skillRepository.findByIdAndClassId(id, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在"));
    }

    private SkillFileParser.ParsedSkill parseOrThrow(String rawText) {
        try {
            return SkillFileParser.parse(rawText);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** description 回退：正文首行截 100 字（文件格式没有独立说明字段）。 */
    private String summarize(String body) {
        String firstLine = body.strip().lines().findFirst().orElse("").strip();
        return firstLine.length() > 100 ? firstLine.substring(0, 100) : firstLine;
    }

    private void requireTeacher(CampusUserPrincipal principal) {
        if (principal.getRole() != UserRole.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有教师可以管理技能");
        }
    }
}
