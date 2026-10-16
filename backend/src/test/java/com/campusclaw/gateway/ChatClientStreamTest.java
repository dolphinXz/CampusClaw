package com.campusclaw.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.gateway.ChatClient.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ChatClient.stream 单测：用本地 HTTP server 喂 OpenAI 兼容 SSE 样本，
 * 校验增量回调、全文累计、[DONE] 结束与故障语义（不泄漏密钥）。
 */
class ChatClientStreamTest {

    private HttpServer server;
    private String baseUrl;
    private ChatClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new ChatClient(baseUrl, "test-key", "course-chat", new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(String body, int status) {
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void streamInvokesDeltaCallbackPerChunkAndReturnsFullText() {
        serve("""
                data: {"choices":[{"delta":{"content":"分数加减"}}]}

                data: {"choices":[{"delta":{"content":"要先通分。"}}]}

                data: [DONE]
                """, 200);

        List<String> deltas = new ArrayList<>();
        String full = client.stream(
                List.of(new Message("user", "分数怎么算")), deltas::add);

        assertThat(deltas).containsExactly("分数加减", "要先通分。");
        assertThat(full).isEqualTo("分数加减要先通分。");
    }

    @Test
    void streamSkipsMalformedDataLines() {
        serve("""
                data: {"choices":[{"delta":{"content":"只有这段"}}]}

                data: not-json-at-all

                data: [DONE]
                """, 200);

        List<String> deltas = new ArrayList<>();
        String full = client.stream(List.of(new Message("user", "q")), deltas::add);

        assertThat(deltas).containsExactly("只有这段");
        assertThat(full).isEqualTo("只有这段");
    }

    @Test
    void non2xxThrowsGatewayExceptionWithoutLeakingDetails() {
        serve("{\"error\":\"invalid key test-key\"}", 401);

        assertThatThrownBy(() -> client.stream(List.of(new Message("user", "q")), t -> {
        }))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("401")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("test-key"));
    }

    @Test
    void connectionFailureThrowsGatewayException() {
        // 未启动任何监听的端口：连接失败
        ChatClient dead = new ChatClient("http://127.0.0.1:65530", "k", "m", new ObjectMapper());

        assertThatThrownBy(() -> dead.stream(List.of(new Message("user", "q")), t -> {
        }))
                .isInstanceOf(GatewayException.class);
    }
}
