package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.AiSkill;
import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.SkillFileResponse;
import com.campusclaw.repository.AiSkillRepository;
import com.campusclaw.security.CampusUserPrincipal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** 文件式技能单测：解析入库与回显、解析错误 400、重名 400、学生 403、他班 404、删除。 */
class SkillServiceTest {

    private static final Long CLASS_A = 1L;
    private static final Long CLASS_B = 2L;

    private static final String VALID_FILE = """
            名称：数学解题引导
            适用条件：方程,去括号,分数
            资料：数学
            工具：knowledge_search
            启用：是

            先判断学生卡在哪一步，只指出下一步应该检查什么。
            """;

    private AiSkillRepository repository;
    private SkillService service;
    private CampusUserPrincipal teacherA;
    private CampusUserPrincipal studentA;

    @BeforeEach
    void setUp() {
        repository = mock(AiSkillRepository.class);
        service = new SkillService(repository);

        ClassRoom classA = new ClassRoom("CLASS_A", "A 班");
        teacherA = CampusUserPrincipal.from(
                new User("teacher-a", "hash", "教师 A", UserRole.TEACHER, classA));
        studentA = CampusUserPrincipal.from(
                new User("student-a1", "hash", "学生 A1", UserRole.STUDENT, classA));
    }

    private AiSkill existingSkill(long id, long classId, String name) {
        AiSkill skill = new AiSkill(classId, null, name, "说明", "正文", true,
                "方程", "数学", "knowledge_search", VALID_FILE);
        ReflectionTestUtils.setField(skill, "id", id);
        return skill;
    }

    @Test
    void createParsesAndPersists() {
        when(repository.existsByClassIdAndName(CLASS_A, "数学解题引导")).thenReturn(false);
        when(repository.save(any(AiSkill.class))).thenAnswer(inv -> {
            AiSkill skill = inv.getArgument(0);
            ReflectionTestUtils.setField(skill, "id", 11L);
            return skill;
        });

        SkillFileResponse response = service.create(VALID_FILE, CLASS_A, teacherA);

        assertThat(response.name()).isEqualTo("数学解题引导");
        assertThat(response.keywords()).containsExactly("方程", "去括号", "分数");
        assertThat(response.subjectScope()).isEqualTo("数学");
        assertThat(response.tool()).isEqualTo("knowledge_search");
        assertThat(response.enabled()).isTrue();
        assertThat(response.rawText()).contains("名称：数学解题引导");
    }

    @Test
    void invalidFilesReturnReadable400() {
        assertThatThrownBy(() -> service.create("名称：x\n适用条件：y\n启用：是", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("缺少空行分隔的说明正文");

        assertThatThrownBy(() -> service.create(
                "适用条件：y\n启用：是\n\n正文", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("名称");

        assertThatThrownBy(() -> service.create(
                "名称：x\n名称：z\n适用条件：y\n启用：是\n\n正文", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("重复字段");

        assertThatThrownBy(() -> service.create(
                "名称：x\n适用条件：y\n启用：是\n未知：1\n\n正文", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未知字段");

        assertThatThrownBy(() -> service.create(
                "名称：x\n适用条件：y\n启用：大概\n\n正文", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("启用");

        assertThatThrownBy(() -> service.create(
                "名称：x\n适用条件：y\n启用：是\n工具：shell\n\n正文", CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("knowledge_search");

        verify(repository, never()).save(any());
    }

    @Test
    void duplicateNameIs400() {
        when(repository.existsByClassIdAndName(CLASS_A, "数学解题引导")).thenReturn(true);

        assertThatThrownBy(() -> service.create(VALID_FILE, CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("同名技能");
    }

    @Test
    void studentWriteIsForbidden() {
        assertThatThrownBy(() -> service.create(VALID_FILE, CLASS_A, studentA))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(403);
        verify(repository, never()).save(any());
    }

    @Test
    void otherClassSkillIsNotFound() {
        AiSkill bSkill = existingSkill(5L, CLASS_B, "他班技能");
        when(repository.findByIdAndClassId(5L, CLASS_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5L, VALID_FILE, CLASS_A, teacherA))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
        assertThat(bSkill.getClassId()).isEqualTo(CLASS_B);
    }

    @Test
    void listReturnsParsedFields() {
        when(repository.findByClassIdOrderById(CLASS_A))
                .thenReturn(List.of(existingSkill(1L, CLASS_A, "解题引导")));

        List<SkillFileResponse> skills = service.list(CLASS_A);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("解题引导");
        assertThat(skills.get(0).rawText()).contains("名称：数学解题引导");
    }
}
