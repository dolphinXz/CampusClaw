package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.SystemPrompt;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.PromptResponse;
import com.campusclaw.repository.SystemPromptRepository;
import com.campusclaw.security.CampusUserPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 提示词单测：默认值、教师保存、学生 403、空内容 400。 */
class PromptServiceTest {

    private static final Long CLASS_A = 1L;

    private SystemPromptRepository repository;
    private PromptService service;
    private CampusUserPrincipal teacher;
    private CampusUserPrincipal student;

    @BeforeEach
    void setUp() {
        repository = mock(SystemPromptRepository.class);
        service = new PromptService(repository);

        ClassRoom classA = new ClassRoom("CLASS_A", "A 班");
        teacher = CampusUserPrincipal.from(
                new User("teacher-a", "hash", "教师 A", UserRole.TEACHER, classA));
        student = CampusUserPrincipal.from(
                new User("student-a1", "hash", "学生 A1", UserRole.STUDENT, classA));
    }

    @Test
    void getReturnsDefaultWhenNeverSaved() {
        when(repository.findByClassId(CLASS_A)).thenReturn(Optional.empty());

        PromptResponse response = service.get(CLASS_A);

        assertThat(response.isDefault()).isTrue();
        assertThat(response.content()).isEqualTo(PromptService.DEFAULT_PROMPT);
        verify(repository, never()).save(any());
    }

    @Test
    void teacherSaveUpsertsAndReturnsSavedContent() {
        when(repository.findByClassId(CLASS_A)).thenReturn(Optional.empty());
        when(repository.save(any(SystemPrompt.class))).thenAnswer(inv -> {
            SystemPrompt prompt = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(prompt, "id", 7L);
            return prompt;
        });

        PromptResponse response = service.save(CLASS_A, "  你是三年级的解题助手  ", teacher);

        assertThat(response.content()).isEqualTo("你是三年级的解题助手");
        assertThat(response.isDefault()).isFalse();
        verify(repository).save(any(SystemPrompt.class));
    }

    @Test
    void studentSaveIsForbiddenAndDoesNotWrite() {
        assertThatThrownBy(() -> service.save(CLASS_A, "任意内容", student))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((org.springframework.web.server.ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(403);
        verify(repository, never()).save(any());
    }

    @Test
    void blankContentIsBadRequest() {
        assertThatThrownBy(() -> service.save(CLASS_A, "   ", teacher))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((org.springframework.web.server.ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void effectiveContentPrefersSavedOverDefault() {
        SystemPrompt saved = new SystemPrompt(CLASS_A, "已保存的提示词", teacher.getId());
        org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 1L);
        when(repository.findByClassId(CLASS_A)).thenReturn(Optional.of(saved));

        assertThat(service.effectiveContent(CLASS_A)).isEqualTo("已保存的提示词");
    }
}
