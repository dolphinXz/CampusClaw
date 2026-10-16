package com.campusclaw.service;

import com.campusclaw.domain.ChatMessage;
import com.campusclaw.domain.ChatSession;
import com.campusclaw.dto.AskResponse.Citation;
import com.campusclaw.dto.MessageResponse;
import com.campusclaw.dto.SessionDetailResponse;
import com.campusclaw.dto.SessionResponse;
import com.campusclaw.repository.AssistantRepository;
import com.campusclaw.repository.ChatMessageRepository;
import com.campusclaw.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 会话管理：归属单个用户与班级，锁定智能体；查询一律带 ownerId。
 * 他人/他班/不存在的会话与智能体统一 404，不泄露存在性。
 */
@Service
public class SessionService {

    /** 新会话默认标题；首轮问题到达后改为问题前 24 个 Unicode 码点。 */
    public static final String DEFAULT_TITLE = "新的提问";
    public static final int TITLE_CHARS = 24;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AssistantRepository assistantRepository;
    private final ObjectMapper objectMapper;

    public SessionService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            AssistantRepository assistantRepository,
            ObjectMapper objectMapper
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.assistantRepository = assistantRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 新建会话：必须选定本班智能体；对学生要求已启用（他班/不存在/未启用 → 404）。
     * 标题缺省「新的提问」。
     */
    @Transactional
    public SessionResponse create(Long agentId, String title, Long ownerId, Long classId, boolean studentView) {
        var assistant = assistantRepository.findByIdAndClassId(agentId, classId)
                .filter(a -> !studentView || a.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在"));
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            trimmed = DEFAULT_TITLE;
        }
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }
        ChatSession session = sessionRepository.save(
                new ChatSession(classId, ownerId, assistant.getId(), trimmed));
        return SessionResponse.from(session, 0);
    }

    /** 列表按最近更新时间倒序。 */
    @Transactional(readOnly = true)
    public List<SessionResponse> list(Long ownerId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : sessionRepository.countMessagesByOwner(ownerId)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return sessionRepository.findByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(session -> SessionResponse.from(session, counts.getOrDefault(session.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse detail(Long sessionId, Long ownerId) {
        ChatSession session = findOwned(sessionId, ownerId);
        String agentName = assistantRepository.findById(session.getAgentId())
                .map(a -> a.getName())
                .orElse("");
        List<MessageResponse> messages = messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(this::toMessageResponse)
                .toList();
        return new SessionDetailResponse(
                session.getId(), session.getTitle(), session.getCreatedAt(), session.getAgentId(), agentName, messages);
    }

    /** 问答链路用：确认会话归属（他人/他班 → 404）。 */
    @Transactional(readOnly = true)
    public ChatSession findOwned(Long sessionId, Long ownerId) {
        return sessionRepository.findByIdAndOwnerId(sessionId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    /**
     * 首轮问题到达：标题改为问题前 24 个 Unicode 码点；之后只更新时间。
     * 返回是否发生了标题更新。
     */
    @Transactional
    public boolean applyFirstQuestion(ChatSession session, String question) {
        boolean first = messageRepository.findBySessionIdOrderByIdAsc(session.getId()).isEmpty();
        if (first) {
            session.rename(firstCodePoints(question, TITLE_CHARS));
        }
        session.touch();
        sessionRepository.save(session);
        return first;
    }

    /** 触碰更新时间（非首轮消息也按最近更新排序）。 */
    @Transactional
    public void touch(ChatSession session) {
        session.touch();
        sessionRepository.save(session);
    }

    /** 问题前 n 个 Unicode 码点（避免截断代理对）。 */
    static String firstCodePoints(String text, int n) {
        String stripped = text == null ? "" : text.strip();
        int end = stripped.offsetByCodePoints(0, Math.min(n, stripped.codePointCount(0, stripped.length())));
        return stripped.substring(0, end);
    }

    private MessageResponse toMessageResponse(ChatMessage message) {
        List<Citation> citations = parseCitations(message.getCitations());
        return MessageResponse.from(message, citations);
    }

    /** citations JSON 反序列化为引用列表；NULL/异常返回空列表（不把存储细节抛给客户端）。 */
    private List<Citation> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Citation>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
