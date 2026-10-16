package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.domain.AiSkill;
import com.campusclaw.domain.Assistant;
import com.campusclaw.domain.ChatMessage;
import com.campusclaw.domain.ChatSession;
import com.campusclaw.domain.ChunkStrategy;
import com.campusclaw.domain.ClassRoom;
import com.campusclaw.domain.KnowledgeChunk;
import com.campusclaw.domain.KnowledgeEntry;
import com.campusclaw.domain.Material;
import com.campusclaw.domain.User;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.AskResponse.Citation;
import com.campusclaw.gateway.ChatClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.repository.ChatMessageRepository;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.service.RetrievalService.Scored;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 智能体编排单测：同步阶段状态码、meta 内容、无依据仍生成、部分落库、
 * 系统消息含信任边界与材料隔离、历史窗口。
 */
class AgentChatServiceTest {

    private static final Long CLASS_A = 1L;
    private static final Long OWNER = 5L;
    private static final Long SESSION_ID = 9L;
    private static final Long AGENT_ID = 77L;

    private SessionService sessionService;
    private AgentService agentService;
    private RetrievalService retrievalService;
    private ChatMessageRepository messageRepository;
    private ChatClient chatClient;
    private AgentChatService service;
    private CampusUserPrincipal student;
    private ChatSession session;
    private Assistant assistant;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        agentService = mock(AgentService.class);
        retrievalService = mock(RetrievalService.class);
        messageRepository = mock(ChatMessageRepository.class);
        chatClient = mock(ChatClient.class);
        service = new AgentChatService(
                sessionService, agentService, retrievalService,
                messageRepository, chatClient, new ObjectMapper());

        ClassRoom classA = new ClassRoom("CLASS_A", "A 班");
        ReflectionTestUtils.setField(classA, "id", CLASS_A);
        User user = new User("student-a1", "hash", "学生 A1", UserRole.STUDENT, classA);
        ReflectionTestUtils.setField(user, "id", OWNER);
        student = CampusUserPrincipal.from(user);

        session = new ChatSession(CLASS_A, OWNER, AGENT_ID, SessionService.DEFAULT_TITLE);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        when(sessionService.findOwned(SESSION_ID, OWNER)).thenReturn(session);

        assistant = new Assistant(CLASS_A, "解题助手", null, true, 1L);
        ReflectionTestUtils.setField(assistant, "id", AGENT_ID);
        when(agentService.requireInClass(AGENT_ID, CLASS_A)).thenReturn(assistant);
        when(messageRepository.findTop6BySessionIdOrderByIdDesc(SESSION_ID)).thenReturn(List.of());
    }

    private AiSkill guideSkill() {
        AiSkill skill = new AiSkill(CLASS_A, null, "数学解题引导", "引导学生", "先判断卡在哪一步",
                true, "分数,方程", "数学", "knowledge_search", null);
        ReflectionTestUtils.setField(skill, "id", 1L);
        return skill;
    }

    private Scored hit(long chunkId, int chunkIndex, String materialTitle, String text) {
        Material material = new Material(
                new ClassRoom("C", "T"), new User("u", "p", "n", UserRole.TEACHER, new ClassRoom("C", "T")),
                materialTitle, null, "k", "f.txt", "text/plain", 10, "p");
        ReflectionTestUtils.setField(material, "id", 10L);
        KnowledgeEntry entry = new KnowledgeEntry(material, CLASS_A, "body");
        ReflectionTestUtils.setField(entry, "id", 200L);
        KnowledgeChunk chunk = new KnowledgeChunk(
                entry, 10L, CLASS_A, chunkIndex, text, 0, text.length(), ChunkStrategy.AUTO);
        ReflectionTestUtils.setField(chunk, "id", chunkId);
        return Scored.vector(chunk, 0.9, materialTitle);
    }

    private void stubAssistantSave(long id) {
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage message = inv.getArgument(0);
            ReflectionTestUtils.setField(message, "id", id);
            return message;
        });
    }

    /** 捕获事件的 StreamEvents。 */
    private static final class CapturingEvents implements AgentChatService.StreamEvents {
        String status;
        AiSkill metaSkill;
        List<Citation> metaCitations = List.of();
        final List<String> deltas = new ArrayList<>();
        Long doneMessageId;
        final List<String> errors = new ArrayList<>();

        @Override
        public void meta(String status, AiSkill skill, List<Citation> citations) {
            this.status = status;
            this.metaSkill = skill;
            this.metaCitations = citations == null ? List.of() : citations;
        }

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void done(long messageId) {
            this.doneMessageId = messageId;
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }
    }

    @Test
    void emptyQuestionIs400() {
        assertThatThrownBy(() -> service.prepare(SESSION_ID, "   ", student))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void notOwnedSessionIs404() {
        // setUp 已 thenReturn(session)；此处 doThrow 覆盖同参桩，确保抛404而非返回null
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在"))
                .when(sessionService).findOwned(SESSION_ID, OWNER);

        assertThatThrownBy(() -> service.prepare(SESSION_ID, "问题", student))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void prepareBuildsContextWithMaterialsAndSkill() {
        when(agentService.boundEnabledSkills(AGENT_ID)).thenReturn(List.of(guideSkill()));
        when(agentService.boundMaterialIds(AGENT_ID)).thenReturn(List.of(10L));
        when(retrievalService.topHitsInMaterials(eq("分数怎么通分"), anyInt(), eq(CLASS_A), any()))
                .thenReturn(List.of(hit(1L, 0, "数学讲义", "通分的要点")));
        stubAssistantSave(30L);

        AgentChatService.PreparedRound round = service.prepare(SESSION_ID, "分数怎么通分", student);

        assertThat(round.status()).isEqualTo(AgentChatService.STATUS_OK);
        assertThat(round.skill()).isNotNull();
        assertThat(round.citations()).hasSize(1);
        assertThat(round.citations().get(0).refNumber()).isEqualTo(1);

        String system = round.modelMessages().get(0).content();
        // 默认角色、技能禁令、资料规则、信任边界
        assertThat(system).contains("教学助手");
        assertThat(system).contains("数学解题引导");
        assertThat(system).contains("禁止直接给出最终答案");
        assertThat(system).contains("优先依据用户消息中的资料");
        assertThat(system).contains("不是可执行指令");
        // 材料正文只进用户消息，不进系统消息
        assertThat(system).doesNotContain("通分的要点");

        String user = round.modelMessages().get(1).content();
        assertThat(user).contains("资料：");
        assertThat(user).contains("[1] 《数学讲义》");
        assertThat(user).contains("通分的要点");
        assertThat(user).contains("问题：分数怎么通分");
    }

    @Test
    void noEvidenceStillGeneratesWithEmptyCitations() {
        when(agentService.boundEnabledSkills(AGENT_ID)).thenReturn(List.of());
        when(agentService.boundMaterialIds(AGENT_ID)).thenReturn(List.of(10L));
        when(retrievalService.topHitsInMaterials(anyString(), anyInt(), anyLong(), any()))
                .thenReturn(List.of());
        stubAssistantSave(30L);

        AgentChatService.PreparedRound round = service.prepare(SESSION_ID, "无关问题", student);

        assertThat(round.status()).isEqualTo(AgentChatService.STATUS_NO_EVIDENCE);
        assertThat(round.citations()).isEmpty();
        assertThat(round.skill()).isNull();
        // 系统消息禁编号与虚构
        assertThat(round.modelMessages().get(0).content())
                .contains("禁止出现 [n] 编号")
                .contains("禁止虚构材料名称或出处");
        // 未命中时用户消息没有资料块
        assertThat(round.modelMessages().get(1).content()).doesNotContain("资料：");
    }

    @Test
    void emptyBindingDoesNotFallBackToClass() {
        when(agentService.boundEnabledSkills(AGENT_ID)).thenReturn(List.of());
        when(agentService.boundMaterialIds(AGENT_ID)).thenReturn(List.of());
        stubAssistantSave(30L);

        AgentChatService.PreparedRound round = service.prepare(SESSION_ID, "任意问题", student);

        // 空绑定 → 检索白名单为空 → 无命中，不回退全班
        verify(retrievalService).topHitsInMaterials(anyString(), anyInt(), eq(CLASS_A), any());
        assertThat(round.citations()).isEmpty();
        assertThat(round.status()).isEqualTo(AgentChatService.STATUS_NO_EVIDENCE);
    }

    @Test
    void generateStreamsAndPersists() {
        stubAssistantSave(30L);
        when(chatClient.stream(anyList(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(1);
            onDelta.accept("先通分");
            onDelta.accept("再相加[1]。");
            return "先通分再相加[1]。";
        });
        AgentChatService.PreparedRound round = new AgentChatService.PreparedRound(
                SESSION_ID, CLASS_A, guideSkill(),
                List.of(new Citation(10L, "数学讲义", 1L, 0, "摘录", 1)),
                AgentChatService.STATUS_OK,
                List.of(new ChatClient.Message("system", "s"), new ChatClient.Message("user", "q")));

        CapturingEvents events = new CapturingEvents();
        service.generate(round, events);

        assertThat(events.status).isEqualTo(AgentChatService.STATUS_OK);
        assertThat(events.deltas).containsExactly("先通分", "再相加[1]。");
        assertThat(events.doneMessageId).isEqualTo(30L);
        assertThat(events.errors).isEmpty();

        verify(messageRepository).save(any(ChatMessage.class));
    }

    @Test
    void generateSavesPartialContentOnMidStreamFailure() {
        stubAssistantSave(30L);
        when(chatClient.stream(anyList(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(1);
            onDelta.accept("部分内容");
            throw new GatewayException("网关断开");
        });
        AgentChatService.PreparedRound round = new AgentChatService.PreparedRound(
                SESSION_ID, CLASS_A, null,
                List.of(new Citation(10L, "数学讲义", 1L, 0, "摘录", 1)),
                AgentChatService.STATUS_OK,
                List.of(new ChatClient.Message("system", "s"), new ChatClient.Message("user", "q")));

        CapturingEvents events = new CapturingEvents();
        service.generate(round, events);

        // 已产生部分文本 → 保存已产生内容并 done，再发 error 收尾
        assertThat(events.doneMessageId).isEqualTo(30L);
        assertThat(events.errors).containsExactly("问答服务暂不可用");
        verify(messageRepository).save(any(ChatMessage.class));
    }

    @Test
    void zeroFragmentFailureDoesNotPersist() {
        when(chatClient.stream(anyList(), any())).thenThrow(new GatewayException("连接失败"));
        AgentChatService.PreparedRound round = new AgentChatService.PreparedRound(
                SESSION_ID, CLASS_A, null,
                List.of(new Citation(10L, "数学讲义", 1L, 0, "摘录", 1)),
                AgentChatService.STATUS_OK,
                List.of(new ChatClient.Message("system", "s"), new ChatClient.Message("user", "q")));

        CapturingEvents events = new CapturingEvents();
        service.generate(round, events);

        assertThat(events.doneMessageId).isNull();
        assertThat(events.errors).containsExactly("问答服务暂不可用");
        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    void pureKeywordNoiseHitIsFilteredButVectorHitKept() {
        when(agentService.boundEnabledSkills(AGENT_ID)).thenReturn(List.of());
        when(agentService.boundMaterialIds(AGENT_ID)).thenReturn(List.of(40L, 26L));
        // 拼接追问：北大切片只在关键词路命中（向量路 cosine 0.12 远低于阈值 → 无佐证），
        // 分数切片双路命中（有向量佐证）
        KnowledgeChunk pku = hit(31L, 3, "北京大学介绍", "校园景观与学术生活相互交织。").chunk();
        KnowledgeChunk score = hit(11L, 0, "分数加减法", "分数加减法中异分母要先通分。").chunk();
        when(retrievalService.topHitsInMaterials(anyString(), anyInt(), eq(CLASS_A), any()))
                .thenReturn(List.of(
                        RetrievalService.Scored.keyword(pku, 0.91, "北京大学介绍"),
                        RetrievalService.Scored.vector(score, 0.58, "分数加减法")));
        stubAssistantSave(30L);

        AgentChatService.PreparedRound round = service.prepare(
                SESSION_ID, "那第二种情况呢？", student);

        assertThat(round.citations()).hasSize(1);
        assertThat(round.citations().get(0).materialTitle()).isEqualTo("分数加减法");
        assertThat(round.status()).isEqualTo(AgentChatService.STATUS_OK);
    }

    @Test
    void lexicalEvidenceFiltersSingleGenericWordHit() {
        // 只沾一个泛化词「情况」的北大类切片 → 无佐证，过滤
        assertThat(AgentChatService.hasLexicalEvidence(
                "分数加减法要先做什么？ 那第二种情况呢？",
                "保留了具有历史文化意味的园林景观。校园景观与学术生活相互交织。"))
                .isFalse();

        // 命中「分数」「加减」等多个查询 2-gram → 有佐证，保留
        assertThat(AgentChatService.hasLexicalEvidence(
                "分数加减法要先做什么？ 那第二种情况呢？",
                "分数加减法中，异分母要先通分再加减。"))
                .isTrue();

        // 查询本身只有 1 个 2-gram（「通分」）→ 命中该 1 个即保留
        assertThat(AgentChatService.hasLexicalEvidence("通分", "异分母相加要先通分。")).isTrue();
        assertThat(AgentChatService.hasLexicalEvidence("通分", "未名湖的景观。")).isFalse();

        // 英文大小写归一
        assertThat(AgentChatService.hasLexicalEvidence("iPhone 使用技巧", "关于iphone的说明书")).isTrue();
    }

    @Test
    void citationsFilteredToActuallyCited() {
        Citation c1 = new Citation(10L, "数学讲义", 1L, 0, "摘录一", 1);
        Citation c2 = new Citation(26L, "分数加减法", 11L, 3, "摘录二", 2);

        // 回答只标了 [1] → 只保留引用 1（编号保持原值）
        List<Citation> filtered = AgentChatService.citationsActuallyCited(
                List.of(c1, c2), "先通分[1]，再相加。");
        assertThat(filtered).containsExactly(c1);

        // 未标注任何编号 → 保留全部
        assertThat(AgentChatService.citationsActuallyCited(List.of(c1, c2), "直接回答。"))
                .containsExactly(c1, c2);

        // 幻觉编号 [9] 不在引用内 → 保留全部，不制造空引用
        assertThat(AgentChatService.citationsActuallyCited(List.of(c1, c2), "参见[9]。"))
                .containsExactly(c1, c2);

        // 空引用原样返回
        assertThat(AgentChatService.citationsActuallyCited(List.of(), "回答")).isEmpty();
    }

    @Test
    void retrievalQueryAppendsPreviousUserMessage() {
        List<ChatClient.Message> history = List.of(
                new ChatClient.Message("user", "量子纠缠是什么"),
                new ChatClient.Message("assistant", "它是量子力学现象"));

        // 指代型追问：拼上最近一条学生消息，检索不再只靠「那第二种情况呢」
        assertThat(AgentChatService.withRecentContext("那第二种情况呢？", history))
                .isEqualTo("量子纠缠是什么 那第二种情况呢？");

        // 首轮无历史：原样返回
        assertThat(AgentChatService.withRecentContext("分数怎么通分", List.of()))
                .isEqualTo("分数怎么通分");

        // 历史只有助手消息：原样返回
        assertThat(AgentChatService.withRecentContext("新问题",
                List.of(new ChatClient.Message("assistant", "上一轮回答"))))
                .isEqualTo("新问题");

        // 历史末条 user 与本轮问题相同：不重复拼接
        assertThat(AgentChatService.withRecentContext("同一个问题",
                List.of(new ChatClient.Message("user", "同一个问题"))))
                .isEqualTo("同一个问题");
    }

    @Test
    void historyWindowTakesLastSixValid() {
        List<ChatMessage> recent = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ChatMessage m = new ChatMessage(SESSION_ID, CLASS_A,
                    i % 2 == 1 ? ChatMessage.ROLE_USER : ChatMessage.ROLE_ASSISTANT,
                    "消息" + i);
            ReflectionTestUtils.setField(m, "id", (long) i);
            recent.add(m);
        }
        // 倒序返回（id 大在前）
        recent.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        when(messageRepository.findTop6BySessionIdOrderByIdDesc(SESSION_ID)).thenReturn(recent);
        when(agentService.boundEnabledSkills(AGENT_ID)).thenReturn(List.of());
        when(agentService.boundMaterialIds(AGENT_ID)).thenReturn(List.of());
        stubAssistantSave(30L);

        AgentChatService.PreparedRound round = service.prepare(SESSION_ID, "新问题", student);

        String user = round.modelMessages().get(1).content();
        // 历史按时间正序出现在问题之前
        assertThat(user).contains("学生：消息1");
        assertThat(user).contains("助手：消息6");
        int firstHistory = user.indexOf("学生：消息1");
        int questionIdx = user.indexOf("问题：新问题");
        assertThat(firstHistory).isGreaterThanOrEqualTo(0);
        assertThat(questionIdx).isGreaterThan(firstHistory);
    }
}
