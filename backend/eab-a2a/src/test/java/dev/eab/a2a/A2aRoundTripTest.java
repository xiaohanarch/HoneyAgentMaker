package dev.eab.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** 用官方 SDK 的客户端调用官方 SDK 装配的服务端，验证往返：流式完成、多轮 input-required、超时转异步、对方停机 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A2aRoundTripTest {

    HttpServer server;
    String base;
    AgentCard card;

    static AgentExecutor executor() {
        return (context, emitter) -> {
            String text = context.getUserInput();
            if (context.getTask() == null) emitter.submit();
            emitter.startWork();
            if (text.contains("慢")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                emitter.addArtifact(List.of(new TextPart("慢任务完成")));
                emitter.complete();
                return;
            }
            if (!text.contains("百润")) {
                emitter.requiresInput(emitter.newAgentMessage(List.of(new TextPart("请告诉我供应商名称")), Map.of()));
                return;
            }
            emitter.addArtifact(List.of(new TextPart("百润精密：风险等级 中")));
            emitter.complete();
        };
    }

    @BeforeAll
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        card = AgentCard.builder()
                .name("测试 Agent")
                .description("往返测试")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(true)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("risk")
                        .name("风险")
                        .description("查风险")
                        .tags(List.of("risk"))
                        .build()))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", base + "/a2a")))
                .build();
        var a2a = new A2aServer(
                card, executor(), Executors.newVirtualThreadPerTaskExecutor(), PushNotificationUrlValidator.ALLOW_ALL);
        server.createContext("/.well-known/agent-card.json", ex -> write(ex, a2a.cardJson()));
        server.createContext("/a2a", ex -> serve(ex, a2a));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static void serve(HttpExchange ex, A2aServer a2a) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var response = a2a.handle(
                body, A2aServer.context("tester", Map.of(), ex.getRequestHeaders().getFirst(A2aServer.VERSION_HEADER)));
        if (response instanceof A2aServer.Response.Json json) {
            write(ex, json.body());
            return;
        }
        var stream = (A2aServer.Response.Stream) response;
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        CountDownLatch done = new CountDownLatch(1);
        stream.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                try {
                    out.write(item.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.close();
    }

    static void write(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("拉取 Agent Card，流式发送消息得到完成的任务，并记录请求与响应原文（令牌只记类型）")
    void roundTrip() {
        var fetched = A2aOutbound.fetchCard(base, null, Map.of());
        assertThat(fetched.card().name()).isEqualTo("测试 Agent");
        assertThat(fetched.json()).contains("测试 Agent");
        var outcome =
                A2aOutbound.send(fetched.card(), "token-x", "百润精密最近有没有风险", null, null, Duration.ofSeconds(10), null);
        assertThat(outcome.state()).isEqualTo("completed");
        assertThat(outcome.text()).contains("风险等级 中");
        assertThat(outcome.exchanges())
                .anyMatch(e -> e.kind().equals("request") && e.body().contains("百润精密"));
        assertThat(outcome.exchanges().toString()).contains("Bearer ***").doesNotContain("token-x");
    }

    @Test
    @DisplayName("多轮：对方追问时返回 input-required，带着 contextId 和 taskId 回答后完成")
    void multiTurn() {
        var first = A2aOutbound.send(card, null, "最近有没有风险", null, null, Duration.ofSeconds(10), null);
        assertThat(first.state()).isEqualTo("input-required");
        assertThat(first.text()).contains("供应商名称");
        var second =
                A2aOutbound.send(card, null, "百润精密", first.contextId(), first.taskId(), Duration.ofSeconds(10), null);
        assertThat(second.state()).isEqualTo("completed");
        assertThat(second.taskId()).isEqualTo(first.taskId());
    }

    @Test
    @DisplayName("超过超时时间转为异步任务，之后按任务编号查到完成的结果")
    void timeoutBecomesAsync() throws Exception {
        var outcome = A2aOutbound.send(card, null, "慢一点", null, null, Duration.ofMillis(300), null);
        assertThat(outcome.async()).isTrue();
        assertThat(outcome.state()).isEqualTo("working");
        assertThat(outcome.taskId()).isNotBlank();
        Thread.sleep(2000);
        var later = A2aOutbound.getTask(card, null, outcome.taskId());
        assertThat(later.state()).isEqualTo("completed");
        assertThat(later.text()).contains("慢任务完成");
    }

    @Test
    @DisplayName("对方停机时给出明确的错误")
    void unreachable() {
        assertThatThrownBy(() -> A2aOutbound.fetchCard("http://127.0.0.1:1", null, Map.of()))
                .isInstanceOf(A2aOutbound.A2aException.class)
                .hasMessageContaining("无法连接外部 Agent");
    }
}
