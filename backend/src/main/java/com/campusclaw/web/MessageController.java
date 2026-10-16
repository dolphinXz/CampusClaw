package com.campusclaw.web;

import com.campusclaw.domain.AiSkill;
import com.campusclaw.dto.AskResponse.Citation;
import com.campusclaw.dto.MessageSendRequest;
import com.campusclaw.gateway.GatewayException;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import com.campusclaw.service.AgentChatService;
import com.campusclaw.service.AgentChatService.PreparedRound;
import com.campusclaw.service.AgentChatService.StreamEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 智能体流式问答：POST /api/sessions/{id}/messages → text/event-stream。
 *
 * 事件协议：
 *   event: meta  data: {"status":"ok|no_evidence","skill":{…}|null,"citations":[…]}
 *   event: delta data: {"text":"…"}              （只发新增片段）
 *   event: done  data: {"messageId":…}           （落库后发出）
 *   event: error data: {"message":"…"}           （脱敏）
 *
 * meta 之前的失败（同步阶段）返回真实 HTTP 状态；meta 之后只能 error 收尾。
 * 已产生部分文本再失败时保存已产生内容；零片段不落库。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/messages")
public class MessageController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final AgentChatService agentChatService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MessageController(AgentChatService agentChatService, ObjectMapper objectMapper) {
        this.agentChatService = agentChatService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(
            @PathVariable("sessionId") Long sessionId,
            @Valid @RequestBody MessageSendRequest request,
            Authentication authentication,
            HttpServletResponse httpResponse
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);

        // 禁代理缓冲：增量逐段到达，不被攒成一次性返回
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        // 同步准备：空问题 400、会话/智能体 404、检索依赖故障 503（meta 之前 → 真实 HTTP 状态）
        PreparedRound prepared;
        try {
            prepared = agentChatService.prepare(sessionId, request.question(), principal);
        } catch (GatewayException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "检索服务暂不可用");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StreamEvents events = new SseStreamEvents(emitter, objectMapper);

        // 异步阶段：meta → delta* → done / error（SecurityContext 不跨线程，principal 数据已取完）
        executor.execute(() -> {
            try {
                agentChatService.generate(prepared, events);
                emitter.complete();
            } catch (Exception e) {
                events.error("问答服务暂不可用");
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 把 meta/delta/done/error 写成 SSE 帧；客户端断开等 IO 失败静默结束。 */
    private static final class SseStreamEvents implements StreamEvents {

        private final SseEmitter emitter;
        private final ObjectMapper objectMapper;

        private SseStreamEvents(SseEmitter emitter, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.objectMapper = objectMapper;
        }

        @Override
        public void meta(String status, AiSkill skill, List<Citation> citations) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", status);
            if (skill == null) {
                data.put("skill", null);
            } else {
                Map<String, Object> skillData = new LinkedHashMap<>();
                skillData.put("name", skill.getName());
                skillData.put("description", skill.getDescription());
                data.put("skill", skillData);
            }
            data.put("citations", citations);
            send("meta", data);
        }

        @Override
        public void delta(String text) {
            send("delta", Map.of("text", text));
        }

        @Override
        public void done(long messageId) {
            send("done", Map.of("messageId", messageId));
        }

        @Override
        public void error(String message) {
            send("error", Map.of("message", message));
        }

        private void send(String event, Object data) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event)
                        .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开或 emitter 已结束：不再发送
                emitter.complete();
            }
        }
    }
}
