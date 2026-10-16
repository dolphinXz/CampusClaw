package com.campusclaw.service;

import com.campusclaw.dto.AskRequest;
import com.campusclaw.dto.AskResponse;
import com.campusclaw.dto.AskResponse.Citation;
import com.campusclaw.gateway.ChatClient;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.service.RetrievalService.Scored;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * /api/ask：先检索、后生成。复用 hybrid 检索取本班前 4 条切片，不另造检索方式。
 *
 * - 有切片：只把材料标题、切片编号、正文和用户问题交给对话模型，[1]、[2] 对应 citations 顺序
 * - 无切片：固定返回「资料中未找到相关内容」，citations 为空，不调用对话模型
 * - 客户端 system 消息一律丢弃，系统提示由服务端生成；
 *   history 最多保留最近 4 条且只帮理解追问，检索只使用最新问题
 * - 不做流式输出
 */
@Service
public class AskService {

    /** 问答固定取 hybrid 前 4 条。 */
    private static final int TOP_CHUNKS = 4;
    /** history 最多保留条数。 */
    private static final int MAX_HISTORY = 4;

    private final RetrievalService retrievalService;
    private final ChatClient chatClient;

    public AskService(RetrievalService retrievalService, ChatClient chatClient) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
    }

    public AskResponse ask(AskRequest request, Long classId) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }

        // 检索只使用最新问题（history 不参与）
        List<Scored> hits = retrievalService.topHits(question, TOP_CHUNKS, classId);

        // 无依据短路：不调用对话模型
        if (hits.isEmpty()) {
            return new AskResponse(
                    AskResponse.NO_EVIDENCE_MESSAGE,
                    "none",
                    List.of()
            );
        }

        List<Citation> citations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Scored hit = hits.get(i);
            citations.add(new Citation(
                    hit.chunk().getMaterialId(),
                    hit.materialTitle(),
                    hit.chunk().getId(),
                    hit.chunk().getChunkIndex(),
                    excerpt(hit.chunk().getChunkText()),
                    i + 1
            ));
            context.append('[').append(i + 1).append("] 《").append(hit.materialTitle())
                    .append("》切片 #").append(hit.chunk().getChunkIndex())
                    .append("：\n").append(hit.chunk().getChunkText()).append("\n\n");
        }

        // 系统提示由服务端生成：模型只能依据材料回答，引用 [n] 对应材料编号
        String systemPrompt = """
                你是校园教研助手。只能依据下面给出的材料回答问题，回答简短。
                引用材料时使用 [1]、[2] 等编号，编号与材料列表顺序一致。
                材料中没有依据时，回答：资料中未找到相关内容。
                不要编造材料中不存在的内容，不要访问任何外部系统。

                材料：
                %s
                """.formatted(context);

        List<ChatClient.Message> messages = new ArrayList<>();
        messages.add(new ChatClient.Message("system", systemPrompt));
        messages.addAll(safeHistory(request.history()));
        messages.add(new ChatClient.Message("user", question));

        String answer;
        try {
            answer = chatClient.complete(messages);
        } catch (GatewayException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "问答服务暂不可用");
        }

        return new AskResponse(answer, "found", citations);
    }

    /**
     * 只接受 user/assistant 角色的历史（客户端 system 消息丢弃），
     * 最多保留最近 MAX_HISTORY 条。
     */
    private List<ChatClient.Message> safeHistory(List<AskRequest.HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatClient.Message> kept = new ArrayList<>();
        for (AskRequest.HistoryMessage item : history) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            String role = item.role() == null ? "" : item.role().trim().toLowerCase();
            if (!role.equals("user") && !role.equals("assistant")) {
                continue; // system 及未知角色一律丢弃
            }
            kept.add(new ChatClient.Message(role, item.content()));
        }
        return kept.size() <= MAX_HISTORY ? kept : kept.subList(kept.size() - MAX_HISTORY, kept.size());
    }

    private String excerpt(String text) {
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
