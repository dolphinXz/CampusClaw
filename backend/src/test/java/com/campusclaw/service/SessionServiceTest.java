package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.Assistant;
import com.campusclaw.domain.ChatSession;
import com.campusclaw.dto.SessionDetailResponse;
import com.campusclaw.dto.SessionResponse;
import com.campusclaw.repository.AssistantRepository;
import com.campusclaw.repository.ChatMessageRepository;
import com.campusclaw.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** 会话单测：智能体校验矩阵、24 码点标题、归属 404、排序。 */
class SessionServiceTest {

    private static final Long CLASS_A = 1L;
    private static final Long OWNER = 5L;
    private static final Long AGENT_ID = 9L;

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private AssistantRepository assistantRepository;
    private SessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        assistantRepository = mock(AssistantRepository.class);
        service = new SessionService(
                sessionRepository, messageRepository, assistantRepository, new ObjectMapper());
    }

    private Assistant assistant(long id, long classId, boolean enabled) {
        Assistant assistant = new Assistant(classId, "解题助手", null, enabled, 1L);
        ReflectionTestUtils.setField(assistant, "id", id);
        return assistant;
    }

    private ChatSession session(long id, long ownerId, long classId) {
        ChatSession session = new ChatSession(classId, ownerId, AGENT_ID, "会话标题");
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    @Test
    void createRequiresEnabledAgentInClass() {
        when(assistantRepository.findByIdAndClassId(AGENT_ID, CLASS_A))
                .thenReturn(Optional.of(assistant(AGENT_ID, CLASS_A, true)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession created = inv.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 3L);
            return created;
        });

        SessionResponse response = service.create(AGENT_ID, null, OWNER, CLASS_A, true);

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.title()).isEqualTo(SessionService.DEFAULT_TITLE);
    }

    @Test
    void studentCreatingWithDisabledAgentIs404() {
        when(assistantRepository.findByIdAndClassId(AGENT_ID, CLASS_A))
                .thenReturn(Optional.of(assistant(AGENT_ID, CLASS_A, false)));

        assertThatThrownBy(() -> service.create(AGENT_ID, null, OWNER, CLASS_A, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void teacherMayUseDisabledAgentForExistingButCreateStillChecks() {
        // 教师视图不强制启用（课件：停用后老会话仍可用；新建校验在学生视图）
        when(assistantRepository.findByIdAndClassId(AGENT_ID, CLASS_A))
                .thenReturn(Optional.of(assistant(AGENT_ID, CLASS_A, false)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession created = inv.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 4L);
            return created;
        });

        SessionResponse response = service.create(AGENT_ID, null, OWNER, CLASS_A, false);
        assertThat(response.id()).isEqualTo(4L);
    }

    @Test
    void otherClassAgentIs404() {
        when(assistantRepository.findByIdAndClassId(AGENT_ID, CLASS_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(AGENT_ID, null, OWNER, CLASS_A, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void firstCodePointsTruncatesByCodePoint() {
        // emoji 占两个 char，必须按码点截 24
        String longText = "很".repeat(24) + "后面的内容";
        assertThat(SessionService.firstCodePoints(longText, 24)).isEqualTo("很".repeat(24));

        String withEmoji = "😀".repeat(30);
        String truncated = SessionService.firstCodePoints(withEmoji, 24);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(24);
    }

    @Test
    void notOwnedSessionIs404() {
        when(sessionRepository.findByIdAndOwnerId(9L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(9L, OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void detailReturnsSessionWithAgentName() {
        when(sessionRepository.findByIdAndOwnerId(9L, OWNER))
                .thenReturn(Optional.of(session(9L, OWNER, CLASS_A)));
        when(assistantRepository.findById(AGENT_ID))
                .thenReturn(Optional.of(assistant(AGENT_ID, CLASS_A, true)));
        when(messageRepository.findBySessionIdOrderByIdAsc(9L)).thenReturn(List.of());

        SessionDetailResponse detail = service.detail(9L, OWNER);

        assertThat(detail.id()).isEqualTo(9L);
        assertThat(detail.agentId()).isEqualTo(AGENT_ID);
        assertThat(detail.agentName()).isEqualTo("解题助手");
        assertThat(detail.messages()).isEmpty();
    }

    @Test
    void listOnlyReturnsOwnSessions() {
        when(sessionRepository.countMessagesByOwner(OWNER))
                .thenReturn(List.<Object[]>of(new Object[]{9L, 3}));
        when(sessionRepository.findByOwnerIdOrderByUpdatedAtDesc(OWNER))
                .thenReturn(List.of(session(9L, OWNER, CLASS_A)));

        List<SessionResponse> sessions = service.list(OWNER);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).messageCount()).isEqualTo(3);
    }
}
