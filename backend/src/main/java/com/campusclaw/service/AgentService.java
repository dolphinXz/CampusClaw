package com.campusclaw.service;

import com.campusclaw.domain.AiSkill;
import com.campusclaw.domain.Assistant;
import com.campusclaw.domain.AssistantBinding;
import com.campusclaw.domain.AssistantMaterialBinding;
import com.campusclaw.domain.Material;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.AgentBindingsResponse;
import com.campusclaw.dto.AgentResponse;
import com.campusclaw.repository.AiSkillRepository;
import com.campusclaw.repository.AssistantBindingRepository;
import com.campusclaw.repository.AssistantMaterialBindingRepository;
import com.campusclaw.repository.AssistantRepository;
import com.campusclaw.repository.MaterialRepository;
import com.campusclaw.security.CampusUserPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 智能体管理：教师创建/修改/启停并维护两类绑定，学生只读（只见已启用）。
 * 他班、不存在、对学生不可见（未启用）的编号一律 404，不泄露存在性。
 */
@Service
public class AgentService {

    /** 无角色提示词时的默认角色。 */
    public static final String DEFAULT_ROLE_PROMPT =
            "你是本班的教学助手，用简明中文回答，只依据提供的资料，不编造。";

    private final AssistantRepository assistantRepository;
    private final AssistantBindingRepository bindingRepository;
    private final AssistantMaterialBindingRepository materialBindingRepository;
    private final AiSkillRepository skillRepository;
    private final MaterialRepository materialRepository;

    public AgentService(
            AssistantRepository assistantRepository,
            AssistantBindingRepository bindingRepository,
            AssistantMaterialBindingRepository materialBindingRepository,
            AiSkillRepository skillRepository,
            MaterialRepository materialRepository
    ) {
        this.assistantRepository = assistantRepository;
        this.bindingRepository = bindingRepository;
        this.materialBindingRepository = materialBindingRepository;
        this.skillRepository = skillRepository;
        this.materialRepository = materialRepository;
    }

    /** 列表：教师看本班全部；学生只见已启用。 */
    @Transactional(readOnly = true)
    public List<AgentResponse> list(Long classId, CampusUserPrincipal principal) {
        List<Assistant> agents = principal.getRole() == UserRole.TEACHER
                ? assistantRepository.findByClassIdOrderById(classId)
                : assistantRepository.findByClassIdAndEnabledTrueOrderById(classId);
        return agents.stream().map(AgentResponse::from).toList();
    }

    @Transactional
    public AgentResponse create(String name, String rolePrompt, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体名称不能为空");
        }
        if (assistantRepository.existsByClassIdAndName(classId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本班已有同名智能体：" + trimmed);
        }
        Assistant assistant = new Assistant(
                classId, trimmed, blankToNull(rolePrompt), true, principal.getId());
        return AgentResponse.from(assistantRepository.save(assistant));
    }

    /** 详情：学生访问未启用/他班/不存在 → 404；教师本班停用可读。 */
    @Transactional(readOnly = true)
    public AgentResponse get(Long id, Long classId, CampusUserPrincipal principal) {
        return AgentResponse.from(findVisible(id, classId, principal));
    }

    @Transactional
    public AgentResponse update(
            Long id, String name, String rolePrompt, Boolean enabled, Long classId, CampusUserPrincipal principal
    ) {
        requireTeacher(principal);
        Assistant assistant = findInClass(id, classId);
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (assistantRepository.existsByClassIdAndNameAndIdNot(classId, trimmed, id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本班已有同名智能体：" + trimmed);
            }
            assistant.apply(trimmed, null, null, principal.getId());
        }
        if (rolePrompt != null) {
            if (rolePrompt.length() > 2000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色提示词最多 2000 字");
            }
            assistant.apply(null, rolePrompt, null, principal.getId());
        }
        if (enabled != null) {
            assistant.apply(null, null, enabled, principal.getId());
        }
        return AgentResponse.from(assistantRepository.save(assistant));
    }

    /** 全量替换技能绑定：任一 id 非本班 → 400，不产生部分绑定。 */
    @Transactional
    public void replaceSkillBindings(Long id, List<Long> skillIds, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        Assistant assistant = findInClass(id, classId);
        List<Long> ids = skillIds == null ? List.of() : skillIds.stream().distinct().toList();
        for (Long skillId : ids) {
            if (!skillRepository.findByIdAndClassId(skillId, classId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技能不存在或不属于本班：" + skillId);
            }
        }
        bindingRepository.deleteByAssistantId(assistant.getId());
        bindingRepository.flush();
        for (Long skillId : ids) {
            bindingRepository.save(new AssistantBinding(assistant.getId(), skillId));
        }
    }

    /** 全量替换材料绑定：任一 id 非本班 → 400。 */
    @Transactional
    public void replaceMaterialBindings(
            Long id, List<Long> materialIds, Long classId, CampusUserPrincipal principal
    ) {
        requireTeacher(principal);
        Assistant assistant = findInClass(id, classId);
        List<Long> ids = materialIds == null ? List.of() : materialIds.stream().distinct().toList();
        for (Long materialId : ids) {
            if (!materialRepository.findById(materialId)
                    .filter(m -> m.getClassRoom().getId().equals(classId))
                    .isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "材料不存在或不属于本班：" + materialId);
            }
        }
        materialBindingRepository.deleteByAssistantId(assistant.getId());
        materialBindingRepository.flush();
        for (Long materialId : ids) {
            materialBindingRepository.save(new AssistantMaterialBinding(assistant.getId(), materialId));
        }
    }

    @Transactional(readOnly = true)
    public AgentBindingsResponse bindings(Long id, Long classId, CampusUserPrincipal principal) {
        requireTeacher(principal);
        findInClass(id, classId);
        List<AgentBindingsResponse.BoundSkill> skills = bindingRepository.findByAssistantId(id).stream()
                .map(b -> skillRepository.findById(b.getSkillId()).orElse(null))
                .filter(s -> s != null)
                .map(s -> new AgentBindingsResponse.BoundSkill(s.getId(), s.getName(), s.isEnabled()))
                .toList();
        List<AgentBindingsResponse.BoundMaterial> materials =
                materialBindingRepository.findByAssistantId(id).stream()
                        .map(b -> materialRepository.findById(b.getMaterialId()).orElse(null))
                        .filter(m -> m != null)
                        .map(m -> new AgentBindingsResponse.BoundMaterial(m.getId(), m.getTitle()))
                        .toList();
        return new AgentBindingsResponse(skills, materials);
    }

    // ---------- 问答链路使用 ----------

    /** 会话链路读取智能体：必须本班；对学生要求已启用（新会话语义在 create 校验，此处供编排复用）。 */
    @Transactional(readOnly = true)
    public Assistant requireInClass(Long agentId, Long classId) {
        return assistantRepository.findByIdAndClassId(agentId, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在"));
    }

    /** 绑定且启用的技能 id → 技能列表（路由候选）。 */
    @Transactional(readOnly = true)
    public List<AiSkill> boundEnabledSkills(Long assistantId) {
        List<AiSkill> result = new ArrayList<>();
        for (AssistantBinding binding : bindingRepository.findByAssistantId(assistantId)) {
            skillRepository.findById(binding.getSkillId())
                    .filter(AiSkill::isEnabled)
                    .ifPresent(result::add);
        }
        return result;
    }

    /** 绑定的材料 id 集合（限定检索范围）。 */
    @Transactional(readOnly = true)
    public List<Long> boundMaterialIds(Long assistantId) {
        return materialBindingRepository.findByAssistantId(assistantId).stream()
                .map(AssistantMaterialBinding::getMaterialId)
                .distinct()
                .toList();
    }

    private Assistant findVisible(Long id, Long classId, CampusUserPrincipal principal) {
        Assistant assistant = assistantRepository.findByIdAndClassId(id, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在"));
        if (principal.getRole() != UserRole.TEACHER && !assistant.isEnabled()) {
            // 学生访问未启用智能体与不存在同表现
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在");
        }
        return assistant;
    }

    private Assistant findInClass(Long id, Long classId) {
        return assistantRepository.findByIdAndClassId(id, classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在"));
    }

    private void requireTeacher(CampusUserPrincipal principal) {
        if (principal.getRole() != UserRole.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有教师可以管理智能体");
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色提示词最多 2000 字");
        }
        return trimmed;
    }
}
